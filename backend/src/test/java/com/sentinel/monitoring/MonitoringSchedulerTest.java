package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
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
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;

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
import org.springframework.data.domain.PageRequest;
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
        //
        // O histórico de checagens (Prompt 10) é filho do monitor com
        // ON DELETE RESTRICT — apagar monitores sem zerar a tabela filha
        // estouraria em violação de FK, então a limpeza é obrigatoriamente
        // nessa ordem.
        monitorCheckRepository.deleteAll();
        monitorRepository.deleteAll();
        // o DueTracker é podado pelo próprio tick (retainOnly), mas o teste
        // do perfil desligado nunca roda um tick — zera explicitamente aqui
        dueTracker.retainOnly(List.of());
        stubTransport.reset();
        clock.reset();
    }

    // --- helpers dos testes de estado --------------------------------------

    /** Um tick completo: despacha, executa e espera persistir + avaliar. */
    private void runOneCheck() {
        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(dispatcher.awaitIdle(FIVE_SECONDS))
                .as("check must have finished (persisted and evaluated)")
                .isTrue();
    }

    /** Estado atual do serviço, lido do banco (a avaliação usa transação própria). */
    private ServiceStatus statusOf(String serviceId) {
        return serviceRepository.findById(UUID.fromString(serviceId)).orElseThrow().getStatus();
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

    // --- Prompt 10: estado operacional -------------------------------------

    @Test
    void serviceWalksTheWholeStatusTableAndRecovers() throws Exception {
        Scene scene = newScene("mon-engine-status", "Engine Status");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Estado");

        // linha 1: nunca houve evidência alguma
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.UNKNOWN);

        // saúde total → OPERATIONAL (evidência positiva vale sozinha)
        stubTransport.respondWith(200);
        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.OPERATIONAL);
        clock.advance(INTERVAL);

        // 500 respondido → DEGRADED: existe, mas não funciona
        stubTransport.respondWith(500);
        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.DEGRADED);
        clock.advance(INTERVAL);

        // ninguém responde mais: a janela precisa de 3 inacessíveis CHEIOS
        stubTransport.failWith(new ConnectException("connection refused"));
        runOneCheck();
        assertThat(statusOf(scene.serviceId()))
                .as("janela [inacessível, 500, 200] ainda é degradação")
                .isEqualTo(ServiceStatus.DEGRADED);
        clock.advance(INTERVAL);

        runOneCheck();
        assertThat(statusOf(scene.serviceId()))
                .as("janela [inacessível, inacessível, 500] ainda é degradação")
                .isEqualTo(ServiceStatus.DEGRADED);
        clock.advance(INTERVAL);

        runOneCheck();
        assertThat(statusOf(scene.serviceId()))
                .as("janela cheia de inacessibilidade = UNAVAILABLE")
                .isEqualTo(ServiceStatus.UNAVAILABLE);
        clock.advance(INTERVAL);

        // volta a responder: a janela móvel sai de UNAVAILABLE já no 1º check
        stubTransport.respondWith(200);
        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.DEGRADED);
        clock.advance(INTERVAL);

        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.DEGRADED);
        clock.advance(INTERVAL);

        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.OPERATIONAL);
    }

    @Test
    void threeHttpErrorsKeepTheServiceDegradedAndNeverUnavailable() throws Exception {
        Scene scene = newScene("mon-engine-http500", "Engine Http 500");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Erro 500");

        stubTransport.respondWith(500);
        for (int i = 0; i < 3; i++) {
            runOneCheck();
            clock.advance(INTERVAL);
        }

        assertThat(statusOf(scene.serviceId()))
                .as("janela cheia de erro respondido = DEGRADED (o alvo responde)")
                .isEqualTo(ServiceStatus.DEGRADED);
        assertThat(monitorCheckRepository.count()).isEqualTo(3);
    }

    @Test
    void redirectWindowPersistsDegradedSignalChecksAndDegradedService() throws Exception {
        Scene scene = newScene("mon-engine-302", "Engine 302");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Redirecionamento");

        stubTransport.respondWith(302);
        for (int i = 0; i < 3; i++) {
            runOneCheck();
            clock.advance(INTERVAL);
        }

        assertThat(statusOf(scene.serviceId()))
                .as("3xx nunca é seguido pelo motor → sinal de degradação")
                .isEqualTo(ServiceStatus.DEGRADED);

        List<MonitorCheck> persisted = monitorCheckRepository
                .findByMonitorIdOrderByStartedAtDesc(UUID.fromString(monitorId), PageRequest.of(0, 10))
                .getContent();
        assertThat(persisted).hasSize(3);
        assertThat(persisted)
                .allSatisfy(check -> {
                    assertThat(check.getOutcome()).isEqualTo(CheckOutcome.DEGRADED_SIGNAL);
                    assertThat(check.getFailureCategory()).isNull();
                    assertThat(check.getHttpStatus()).isEqualTo(302);
                });
    }

    @Test
    void internalFailuresArePersistedButNeverMoveTheServiceStatus() throws Exception {
        Scene scene = newScene("mon-engine-internal", "Engine Internal");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Interna");

        // cenário A — serviço NOVO: só falhas internas do Sentinel. A janela
        // de avaliação as exclui, a evidência fica vazia e o estado é
        // UNKNOWN — nunca UNAVAILABLE (janela interna não é "3 inacessíveis")
        stubTransport.failWith(new IllegalStateException("stub bug"));
        for (int i = 0; i < 3; i++) {
            runOneCheck();
            clock.advance(INTERVAL);
        }
        assertThat(statusOf(scene.serviceId()))
                .as("falha interna não é evidência sobre o alvo")
                .isEqualTo(ServiceStatus.UNKNOWN);

        // cenário B — serviço já OPERATIONAL ganha UMA falha interna: a
        // saúde provada não é derrubada por check do nosso lado
        stubTransport.respondWith(200);
        runOneCheck();
        assertThat(statusOf(scene.serviceId())).isEqualTo(ServiceStatus.OPERATIONAL);
        clock.advance(INTERVAL);

        stubTransport.failWith(new IllegalStateException("stub bug"));
        runOneCheck();
        assertThat(statusOf(scene.serviceId()))
                .as("check interno não tira o serviço de OPERATIONAL")
                .isEqualTo(ServiceStatus.OPERATIONAL);

        // as 5 execuções viraram linha — inclusive as internas (visíveis na
        // API de histórico, invisíveis para a janela de avaliação)
        List<MonitorCheck> persisted = monitorCheckRepository
                .findByMonitorIdOrderByStartedAtDesc(
                        UUID.fromString(monitorId), PageRequest.of(0, 10))
                .getContent();
        assertThat(persisted).hasSize(5);
        assertThat(persisted)
                .extracting(MonitorCheck::getOutcome)
                .containsExactly(
                        CheckOutcome.SENTINEL_INTERNAL_FAILURE,
                        CheckOutcome.SUCCESS,
                        CheckOutcome.SENTINEL_INTERNAL_FAILURE,
                        CheckOutcome.SENTINEL_INTERNAL_FAILURE,
                        CheckOutcome.SENTINEL_INTERNAL_FAILURE);
    }

    // --- append-only --------------------------------------------------------

    @Test
    void everyExecutionPersistsExactlyOneCheckRow() throws Exception {
        Scene scene = newScene("mon-engine-count", "Engine Count");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Contagem");
        UUID id = UUID.fromString(monitorId);

        for (int i = 0; i < 3; i++) {
            runOneCheck();
            clock.advance(INTERVAL);
        }

        assertThat(monitorCheckRepository.count()).isEqualTo(3);
        assertThat(monitorCheckRepository.findByMonitorIdOrderByStartedAtDesc(
                                id, PageRequest.of(0, 10))
                        .getContent())
                .extracting(MonitorCheck::getStartedAt)
                .containsExactly(T0.plus(INTERVAL).plus(INTERVAL), T0.plus(INTERVAL), T0);
    }

    @Test
    void sameStartInstantRowsCoexistBecauseV8HasNoUniqueTimestamp() throws Exception {
        Scene scene = newScene("mon-engine-dupe", "Engine Dupe");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Relógio Congelado");
        Monitor monitor = monitorOf(scene.serviceId(), monitorId);

        // dois registros com o MESMO (monitor_id, started_at) — o que um
        // UNIQUE rejeitaria em silêncio e mataria testes com Clock congelado
        MonitorCheck first = monitorCheckRepository.saveAndFlush(new MonitorCheck(
                monitor, T0, T0.plusMillis(100), 100, 200, CheckOutcome.SUCCESS, null, "primeiro"));
        MonitorCheck second = monitorCheckRepository.saveAndFlush(new MonitorCheck(
                monitor, T0, T0.plusMillis(100), 100, 200, CheckOutcome.SUCCESS, null, "segundo"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(monitorCheckRepository.count()).isEqualTo(2);
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
     * (latch) para exercitar in-flight, devolver QUALQUER status HTTP e pode
     * falhar sob demanda (checked = rede do alvo; runtime = bug nosso).
     */
    static final class StubHttpTransport implements HttpTransport {

        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile RuntimeException failure;
        private volatile IOException ioFailure;
        private volatile Integer responseStatus;

        @Override
        public int send(HttpRequest request) throws IOException, InterruptedException {
            paths.add(request.uri().getPath());
            entered.countDown();
            if (release.getCount() > 0) {
                release.await(10, TimeUnit.SECONDS);
            }
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (failure != null) {
                throw failure;
            }
            return responseStatus == null ? 200 : responseStatus;
        }

        void reset() {
            paths.clear();
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
            failure = null;
            ioFailure = null;
            responseStatus = null;
        }

        /** Próximas respostas devolvem este status HTTP (e nenhuma falha). */
        void respondWith(int httpStatus) {
            this.responseStatus = httpStatus;
            this.failure = null;
            this.ioFailure = null;
        }

        /** Falha de REDE do alvo (ConnectException, UnknownHostException…). */
        void failWith(IOException ex) {
            this.ioFailure = ex;
            this.failure = null;
        }

        /** Bug do nosso lado — vira erro interno, não falha do alvo. */
        void failWith(RuntimeException ex) {
            this.failure = ex;
            this.ioFailure = null;
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
