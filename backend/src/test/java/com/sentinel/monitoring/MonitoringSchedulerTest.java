package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sentinel.project.AbstractMonitorIntegrationTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tick do {@link MonitoringScheduler} (Prompt 09) — o coração do motor,
 * exercitado de ponta a ponta: elegibilidade real no banco (query com JOIN
 * de ativação), vencimento, despacho assíncrono e classificação de
 * resultados.
 *
 * <p><b>Stubs do contexto</b> (única classe que declara este
 * {@code @TestConfiguration}): transporte HTTP gravador (nunca conecta) e
 * relógio controlável — os testes avançam o tempo em vez de dormir. O
 * {@code SsrfGuard} REAL continua no caminho: os monitores usam o literal
 * IPv4 público canônico, que o guard aceita sem DNS.</p>
 *
 * <p><b>Perfil de teste desliga o agendamento</b>
 * ({@code sentinel.monitoring.enabled=false}): nenhum {@code @Scheduled}
 * existe neste contexto, os 346 testes existentes não veem tick algum, e o
 * motor é acionado à mão via {@link MonitoringScheduler#tick()}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringSchedulerTest extends AbstractMonitorIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
    private static final Duration INTERVAL = Duration.ofSeconds(MonitorFixtures.DEFAULT_INTERVAL_SECONDS);

    @Autowired
    private MonitoringScheduler scheduler;

    @Autowired
    private MonitoringDispatcher dispatcher;

    @Autowired
    private DueTracker dueTracker;

    @Autowired
    private MonitoringProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private StubHttpTransport stubTransport;

    @Autowired
    private MutableClock clock;

    /**
     * Nested {@code @TestConfiguration} é detectado apenas por esta classe —
     * os stubs não vazam para os demais contextos de teste. {@code @Primary}
     * desempata contra os beans de produção ({@code JdkHttpTransport},
     * {@code SystemClock}).
     */
    @TestConfiguration
    static class EngineStubs {

        @Bean
        @Primary
        StubHttpTransport stubHttpTransport() {
            return new StubHttpTransport();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @BeforeEach
    void resetEngineState() {
        // base limpa por teste: monitores de cenas anteriores não poluem a
        // elegibilidade nem os contadores do tick atual
        monitorRepository.deleteAll();
        // o DueTracker é podado pelo próprio tick (retainOnly), mas o teste
        // do perfil desligado nunca roda um tick — zera explicitamente aqui
        dueTracker.retainOnly(List.of());
        stubTransport.reset();
        clock.reset();
    }

    // --- vencimento ---------------------------------------------------------

    @Test
    void newMonitorRunsImmediatelyThenWaitsForItsInterval() throws Exception {
        Scene scene = newScene("mon-engine-first", "Engine First");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Primeira Execução");
        UUID id = UUID.fromString(monitorId);

        // primeira execução: monitor novo é due imediatamente
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(stubTransport.paths).containsExactly("/health");
        assertThat(dueTracker.nextExecutionAt(id)).isEqualTo(T0.plus(INTERVAL));

        // mesmo instante → ainda dentro do intervalo
        assertThat(scheduler.tick()).isZero();
        assertThat(stubTransport.paths).hasSize(1);

        // 59s depois → ainda não; no intervalo cheio → executa de novo
        clock.advance(Duration.ofSeconds(59));
        assertThat(scheduler.tick()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(stubTransport.paths).hasSize(2);
        assertThat(dueTracker.nextExecutionAt(id)).isEqualTo(T0.plus(INTERVAL).plus(INTERVAL));
    }

    // --- elegibilidade: ativação -------------------------------------------

    @Test
    void deactivatedMonitorIsPrunedAndReactivatedRunsImmediately() throws Exception {
        Scene scene = newScene("mon-engine-activation", "Engine Activation");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer,
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Ativação");
        UUID id = UUID.fromString(monitorId);

        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();

        changeMonitorActivation(
                bearer,
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                monitorId,
                false);
        assertThat(scheduler.tick()).isZero();
        assertThat(dueTracker.isTracked(id))
                .as("monitor fora da elegibilidade perde o estado de vencimento")
                .isFalse();

        // reativado → primeira execução imediata, sem esperar o intervalo
        changeMonitorActivation(
                bearer,
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                monitorId,
                true);
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(stubTransport.paths).hasSize(2);
    }

    // --- elegibilidade: serviço e projeto -----------------------------------

    @Test
    void inactiveServiceOrProjectStopsExecutionUntilReactivated() throws Exception {
        Scene scene = newScene("mon-engine-hierarchy", "Engine Hierarchy");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer,
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Hierarquia");
        UUID id = UUID.fromString(monitorId);

        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();

        // serviço desativado → monitor nem entra no tick
        changeActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);
        assertThat(scheduler.tick()).isZero();
        assertThat(dueTracker.isTracked(id)).isFalse();

        changeActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), true);
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();

        // projeto desativado → mesma regra
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), false);
        assertThat(scheduler.tick()).isZero();
        assertThat(dueTracker.isTracked(id)).isFalse();

        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), true);
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(stubTransport.paths).hasSize(3);
    }

    // --- in-flight ----------------------------------------------------------

    @Test
    void inFlightMonitorIsNeverDispatchedTwice() throws Exception {
        Scene scene = newScene("mon-engine-inflight", "Engine In Flight");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Em Voo");
        UUID id = UUID.fromString(monitorId);

        stubTransport.block();
        assertThat(scheduler.tick()).isEqualTo(1);
        stubTransport.awaitEntered();

        // segundo tick enquanto a checagem ainda roda: IN_FLIGHT, sem nova
        // requisição e sem novo commit de vencimento
        assertThat(scheduler.tick()).isZero();
        assertThat(stubTransport.paths).hasSize(1);
        assertThat(dueTracker.nextExecutionAt(id)).isEqualTo(T0.plus(INTERVAL));

        stubTransport.unblock();
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();

        // liberada mas ainda dentro do intervalo → não reexecuta
        assertThat(scheduler.tick()).isZero();
        clock.advance(INTERVAL);
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(stubTransport.paths).hasSize(2);
    }

    // --- falha interna ------------------------------------------------------

    @Test
    void failingCheckIsCountedAsInternalAndRetriedOnTheNextInterval() throws Exception {
        long internalErrorsBefore = dispatcher.internalErrorCount();
        Scene scene = newScene("mon-engine-failing", "Engine Failing");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Falhosa");
        stubTransport.failWith(new IllegalStateException("stub bug"));

        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();

        // bug NÃO é falha de alvo: só o contador de erro interno muda
        assertThat(dispatcher.internalErrorCount()).isEqualTo(internalErrorsBefore + 1);
        assertThat(stubTransport.paths).hasSize(1);

        // liberado → reexecuta no próximo vencimento (não "pendura")
        clock.advance(INTERVAL);
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS)).isTrue();
        assertThat(dispatcher.internalErrorCount()).isEqualTo(internalErrorsBefore + 2);
    }

    // --- perfil de teste ----------------------------------------------------

    @Test
    void testProfileKeepsSchedulingDisabledAndTickNeverRuns() throws Exception {
        // contadores do dispatcher são da instância do contexto (somam todos
        // os testes desta classe) — o que importa aqui é o DELTA destes chamados
        long dispatchedBefore = dispatcher.dispatchedCount();

        // a propriedade do perfil test desliga o agendador...
        assertThat(properties.isEnabled()).isFalse();
        // ...e a configuração condicional nem sequer é registrada:
        // nenhum @Scheduled do motor existe neste contexto
        assertThat(applicationContext.getBeansOfType(MonitoringSchedulingConfiguration.class))
                .isEmpty();

        Scene scene = newScene("mon-engine-disabled", "Engine Disabled");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Nunca Agendada");

        // scheduledTick é o caminho do agendador: early-return, zero trabalho
        scheduler.scheduledTick();
        scheduler.scheduledTick();

        assertThat(stubTransport.paths).isEmpty();
        assertThat(dueTracker.size()).isZero();
        assertThat(dispatcher.dispatchedCount()).isEqualTo(dispatchedBefore);
    }

    // --- stubs --------------------------------------------------------------

    /** Relógio controlável: os testes avançam o tempo em vez de dormir. */
    static final class MutableClock implements Clock {

        private volatile Instant now = T0;

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void reset() {
            now = T0;
        }
    }

    /**
     * Transporte gravador: nunca conecta; pode ficar "preso" em uma checagem
     * (latch) para exercitar in-flight e pode falhar sob demanda.
     */
    static final class StubHttpTransport implements HttpTransport {

        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile RuntimeException failure;

        @Override
        public int send(HttpRequest request) throws InterruptedException {
            paths.add(request.uri().getPath());
            entered.countDown();
            if (release.getCount() > 0) {
                release.await(10, TimeUnit.SECONDS);
            }
            if (failure != null) {
                throw failure;
            }
            return 200;
        }

        void reset() {
            paths.clear();
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
            failure = null;
        }

        void failWith(RuntimeException ex) {
            this.failure = ex;
        }

        void block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void unblock() {
            release.countDown();
        }

        void awaitEntered() {
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS))
                        .as("check should have started")
                        .isTrue();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for check", ex);
            }
        }
    }
}
