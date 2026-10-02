package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sentinel.monitoring.Clock;
import com.sentinel.monitoring.MonitoringDispatcher;
import com.sentinel.monitoring.MonitoringProperties;
import com.sentinel.monitoring.MonitoringScheduler;
import com.sentinel.monitoring.MonitorTargetValidator;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;
import com.sentinel.project.monitor.model.MonitorMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ponta a ponta do Prompt 13 — critérios 113–120 contra contexto real.
 *
 * <p><b>O que este teste PROVA que as demais classes não provam:</b> o
 * pipeline COMPLETO do production — {@code MonitoringScheduler.tick()} →
 * despacho no pool → HTTP REAL num servidor local do JDK →
 * {@code MonitorCheckRecorder} → {@code OperationalStateEvaluator} →
 * {@code AlertEngine} — sem nenhuma semeadura manual de check: cada linha de
 * histórico nasce de uma requisição verdadeira.</p>
 *
 * <p><b>Relógio MUTÁVEL (e não o congelado do resto do alert engine):</b>
 * aqui o tempo AVANÇA entre ticks (a janela precisa enxergar checks antigos
 * e descartar falhas que saíram do recorte), então o {@code @Primary Clock}
 * é um {@link MutableClock} controlado pelo teste, não o
 * {@code FixedMetricsClockConfig}. O MESMO bean alimenta executor, métricas,
 * avaliador de estado e lifecycle — um único {@code clock.set()} move o
 * sistema inteiro.</p>
 *
 * <p><b>Dois invariantes de tempo que cada tick respeita:</b></p>
 * <ol>
 *   <li><b>Janela meia-aberta:</b> o check gravado NA própria execução tem
 *       {@code startedAt == agora} e fica DE FORA de
 *       {@code [agora − window, agora)}. Por isso o primeiro tick de um
 *       ciclo nunca dispara nada e o alerta só nasce no tick seguinte;</li>
 *   <li><b>Vencimento:</b> com {@code intervalSeconds = 60}, o segundo tick
 *       de um monitor só acontece com o relógio já em
 *       {@code ≥ anterior + 60s} — o {@code DueTracker} não despacha antes
 *       disso. Os intervalos dos testes (60s, 7min, 17min…) seguem esta
 *       régua.</li>
 * </ol>
 *
 * <p><b>Rede:</b> servidor {@code com.sun.net.httpserver} em loopback com
 * status VOLÁTIL (500 → 200) e um {@code MonitorTargetValidator}
 * {@code @Primary} permissivo — o guard de SSRF de produção bloquearia o
 * loopback COMO DEVE, e recriar o monitor via API passaria pelo guard; o
 * monitor nasce do repositório e a validação de alvo é substituída só neste
 * contexto (mesmo arranjo do {@code MonitorSsrfTest}).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertEngineE2ETest extends AbstractAlertEngineIntegrationTest {

    /** Primeiro tick de todos os testes — base da linha do tempo. */
    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    /** Intervalo dos monitores E2E — define o passo mínimo entre ticks. */
    private static final int INTERVAL_SECONDS = 60;

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    // --- servidor HTTP local -------------------------------------------------

    private static final String BODY = "probe";
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    /** Status que /probe devolve — cada teste define o seu. */
    private static volatile int responseStatus = 500;

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static String baseUrl;

    @BeforeAll
    static void startInfrastructure() throws IOException {
        postgres.start();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext(
                "/probe",
                exchange -> {
                    REQUESTS.incrementAndGet();
                    respond(exchange, responseStatus, BODY);
                });
        server.start();
        baseUrl =
                "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopInfrastructure() {
        server.stop(0);
        serverExecutor.shutdownNow();
        postgres.stop();
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    // --- infraestrutura do contexto ------------------------------------------

    /**
     * Relógio controlável — {@code @Primary} sobre o {@code SystemClock} do
     * component scan. O tipo concreto é injetável nos testes; qualquer bean
     * que pede a interface {@link Clock} recebe este.
     */
    static final class MutableClock implements Clock {

        private final AtomicReference<Instant> current = new AtomicReference<>(T0);

        @Override
        public Instant instant() {
            return current.get();
        }

        void set(Instant instant) {
            current.set(instant);
        }
    }

    /**
     * Nested {@code @TestConfiguration} detectada só por esta classe: relógio
     * mutável + validador de alvo que aceita o loopback (o guard real
     * bloquearia, e é exatamente o guard que queremos fora do caminho aqui —
     * a segurança em si é exercitada pelo {@code MonitorSsrfTest}).
     */
    @TestConfiguration
    static class LocalPipelineConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        MonitorTargetValidator localTargetAllowed() {
            return url -> {
                // servidor local do teste: sem revalidação de SSRF
            };
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private MonitoringScheduler monitoringScheduler;

    @Autowired
    private MonitoringDispatcher monitoringDispatcher;

    @Autowired
    private MonitoringProperties monitoringProperties;

    // --- helpers -------------------------------------------------------------

    /**
     * Um tick COMPLETO: move o relógio, dispara o pipeline e espera todas as
     * tasks do pool terminarem (gravar → estado → alertas) antes de qualquer
     * asserção — sem a espera, as leituras correriam contra o banco no meio
     * da execução.
     */
    private void tickAt(Instant instant) {
        clock.set(instant);
        monitoringScheduler.tick();
        assertThat(monitoringDispatcher.awaitIdle(Duration.ofSeconds(15)))
                .as("o pipeline (gravar → estado → alertas) terminou antes do timeout")
                .isTrue();
    }

    /** Serviço da cena, recarregado do banco (estado pós-tick). */
    private Service serviceOf(Scene scene) {
        return serviceRepository.findById(UUID.fromString(scene.serviceId())).orElseThrow();
    }

    /**
     * Monitor apontando para o servidor local, criado pelo REPOSITÓRIO: a
     * criação via API revalidaria o alvo contra o guard de SSRF (que barra
     * loopback como deve) e falharia.
     */
    private String localMonitor(Scene scene, String name) {
        Monitor monitor = monitorRepository.save(new Monitor(
                serviceOf(scene), name, baseUrl + "/probe",
                MonitorMethod.GET, INTERVAL_SECONDS, 5000));
        return monitor.getId().toString();
    }

    /** Checks persistidos de UM monitor, mais recente primeiro. */
    private List<MonitorCheck> checksOf(String monitorId) {
        return monitorCheckRepository
                .findRelevantByMonitorIdOrderByStartedAtDesc(
                        UUID.fromString(monitorId), PageRequest.of(0, 20))
                .getContent();
    }

    // --- #113: HTTP real → histórico real ------------------------------------

    @Test
    void criterio113_tick_executa_http_real_e_persiste_check() throws Exception {
        Scene scene = newScene("e2e-check", "Org AlertEngine E2E Check");
        String monitorId = localMonitor(scene, "Monitor E2E check");
        responseStatus = 500;
        int requestsBefore = REQUESTS.get();

        tickAt(T0);

        assertThat(REQUESTS.get())
                .as("o tick bateu no servidor HTTP local de verdade")
                .isGreaterThan(requestsBefore);
        List<MonitorCheck> checks = checksOf(monitorId);
        assertThat(checks).hasSize(1);
        MonitorCheck check = checks.get(0);
        assertThat(check.getOutcome()).isEqualTo(CheckOutcome.TARGET_FAILURE);
        assertThat(check.getHttpStatus()).isEqualTo(500);
        assertThat(check.getFailureCategory()).isEqualTo(CheckFailureCategory.HTTP_ERROR);
        assertThat(check.getStartedAt()).isEqualTo(T0);
        assertThat(check.getFinishedAt()).isNotNull();
        assertThat(check.getMessage()).contains("HTTP 500");
    }

    // --- #114: avaliador de estado grava status + eixo -----------------------

    @Test
    void criterio114_avaliador_grava_status_e_eixo_apenas_na_transicao() throws Exception {
        Scene scene = newScene("e2e-state", "Org AlertEngine E2E State");
        localMonitor(scene, "Monitor E2E state");
        responseStatus = 500;

        Service service = serviceOf(scene);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(service.getStatusChangedAt())
                .as("serviço recém-criado não tem transição registrada")
                .isNull();

        tickAt(T0);

        service = serviceOf(scene);
        assertThat(service.getStatus())
                .as("500 = falha respondida → DEGRADED (não UNAVAILABLE)")
                .isEqualTo(ServiceStatus.DEGRADED);
        assertThat(service.getStatusChangedAt())
                .as("a transição grava o eixo temporal")
                .isEqualTo(T0);

        tickAt(T0.plusSeconds(INTERVAL_SECONDS));

        service = serviceOf(scene);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(service.getStatusChangedAt())
                .as("sem mudança de status não há reescrita: o eixo segue no T0")
                .isEqualTo(T0);
    }

    // --- #115: pipeline completo → FIRING legível na API ---------------------

    @Test
    void criterio115_pipeline_completo_dispara_firing_legivel_na_api() throws Exception {
        Scene scene = newScene("e2e-fire", "Org AlertEngine E2E Fire");
        String monitorId = localMonitor(scene, "Monitor E2E fire");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra E2E firing");
        responseStatus = 500;

        tickAt(T0);

        assertThat(alertsOf(scene.serviceId()))
                .as("o check da própria execução está na borda da janela meia-aberta: ainda não conta")
                .isEmpty();

        tickAt(T0.plusSeconds(INTERVAL_SECONDS));

        assertThat(checksOf(monitorId))
                .as("dois ticks, dois checks reais")
                .hasSize(2);
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getAlertRule().getId()).hasToString(ruleId);
        assertThat(alert.getMonitorId())
                .as("regra service-level não herda monitor")
                .isNull();
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV3);
        assertThat(alert.getStartedAt()).isEqualTo(T0.plusSeconds(INTERVAL_SECONDS));

        // contrato de leitura: o MESMO alerta aparece na API
        mockMvc.perform(get(alertsUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("FIRING"))
                .andExpect(jsonPath("$.content[0].id").value(alert.getId().toString()));
    }

    // --- #116: evidência real avaliada ---------------------------------------

    @Test
    void criterio116_alerta_carrega_evidencia_real_da_avaliacao() throws Exception {
        Scene scene = newScene("e2e-evidence", "Org AlertEngine E2E Evidence");
        localMonitor(scene, "Monitor E2E evidence");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra E2E evidencia");
        responseStatus = 500;

        tickAt(T0);
        tickAt(T0.plusSeconds(INTERVAL_SECONDS));

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getAlertRule().getId()).hasToString(ruleId);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.AVAILABILITY);
        assertThat(alert.getRuleWindow()).isEqualTo(EvaluationWindow.FIFTEEN_MINUTES);
        assertThat(alert.getRuleThresholdNumeric())
                .isEqualByComparingTo(new BigDecimal("99.9"));
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV3);
        assertThat(alert.getTriggerValue())
                .as("0 sucesso em 1 relevante dentro da janela")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(alert.getMessage())
                .as("mensagem = nome da regra + detalhe do avaliador")
                .isEqualTo("Regra E2E evidencia: availability 0.00%"
                        + " against threshold 99.90% over 15 minutes");
        assertThat(alert.getResolutionReason()).isNull();
        assertThat(alert.getLastEvaluatedAt()).isEqualTo(T0.plusSeconds(INTERVAL_SECONDS));
        assertThat(alert.getMonitorId()).isNull();
    }

    // --- #117: recuperação resolve AUTO preservando a mensagem ---------------

    @Test
    void criterio117_recuperacao_resolve_auto_preservando_a_mensagem() throws Exception {
        Scene scene = newScene("e2e-recover", "Org AlertEngine E2E Recover");
        localMonitor(scene, "Monitor E2E recover");
        Map<String, Object> body = serviceLevelRule("Regra E2E recuperacao");
        body.put("window", "FIVE_MINUTES");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), body);
        responseStatus = 500;

        tickAt(T0);
        tickAt(T0.plusSeconds(INTERVAL_SECONDS));
        Alert firing = singleAlertOf(scene.serviceId());
        assertThat(firing.getStatus()).isEqualTo(AlertStatus.FIRING);
        String firingMessage = firing.getMessage();

        // alvo volta ao ar; as duas falhas ainda estão dentro dos 5 minutos
        responseStatus = 200;
        Instant healthyTick = T0.plusSeconds(7 * 60);
        tickAt(healthyTick);

        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .as("sem relevante na janela → NO_DATA NUNCA resolve")
                .isEqualTo(AlertStatus.FIRING);
        assertThat(singleAlertOf(scene.serviceId()).getMessage())
                .as("NO_DATA não toca na mensagem do FIRING")
                .isEqualTo(firingMessage);

        // falhas saem da janela; só o check saudável resta → HEALTHY → AUTO
        Instant resolvedTick = T0.plusSeconds(8 * 60);
        tickAt(resolvedTick);

        Alert resolved = singleAlertOf(scene.serviceId());
        assertThat(resolved.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.getResolutionReason()).isEqualTo(AlertResolutionReason.AUTO);
        assertThat(resolved.getResolvedAt()).isEqualTo(resolvedTick);
        assertThat(resolved.getMessage())
                .as("a resolução preserva a evidência do disparo")
                .isEqualTo(firingMessage);
    }

    // --- #118: reabertura após resolução = SEGUNDO alerta --------------------

    @Test
    void criterio118_reabertura_apos_resolucao_abre_segundo_alerta() throws Exception {
        Scene scene = newScene("e2e-reopen", "Org AlertEngine E2E Reopen");
        localMonitor(scene, "Monitor E2E reopen");
        Map<String, Object> body = serviceLevelRule("Regra E2E reabertura");
        body.put("window", "FIVE_MINUTES");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), body);
        responseStatus = 500;

        // ciclo 1: dispara e resolve (mesma linha do tempo do #117)
        tickAt(T0);
        tickAt(T0.plusSeconds(INTERVAL_SECONDS));
        String firstMessage = singleAlertOf(scene.serviceId()).getMessage();
        responseStatus = 200;
        tickAt(T0.plusSeconds(7 * 60));
        tickAt(T0.plusSeconds(8 * 60));
        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .as("ciclo 1 encerrado antes de reabrir")
                .isEqualTo(AlertStatus.RESOLVED);

        // ciclo 2: falha de novo — a saudável ainda cobre a janela no 1º tick
        responseStatus = 500;
        tickAt(T0.plusSeconds(9 * 60));

        List<Alert> all = alertsOf(scene.serviceId());
        assertThat(all)
                .as("ainda só o alerta resolvido: a falha nova está na borda")
                .hasSize(1);

        tickAt(T0.plusSeconds(10 * 60));

        all = alertsOf(scene.serviceId());
        assertThat(all).hasSize(2);
        Alert resolvedOld = all.stream()
                .filter(a -> a.getStatus() == AlertStatus.RESOLVED)
                .findFirst()
                .orElseThrow();
        Alert newFiring = all.stream()
                .filter(a -> a.getStatus() == AlertStatus.FIRING)
                .findFirst()
                .orElseThrow();

        assertThat(resolvedOld.getResolutionReason()).isEqualTo(AlertResolutionReason.AUTO);
        assertThat(resolvedOld.getResolvedAt()).isEqualTo(T0.plusSeconds(8 * 60));
        assertThat(resolvedOld.getMessage())
                .as("o alerta antigo permanece com a evidência original")
                .isEqualTo(firstMessage);
        assertThat(newFiring.getStartedAt()).isEqualTo(T0.plusSeconds(10 * 60));
        assertThat(newFiring.getAlertRule().getId())
                .as("reabertura é UM NOVO alerta da MESMA regra")
                .hasToString(ruleId);
        assertThat(openAlertsOf(scene.serviceId()))
                .as("aberto = apenas o novo; o antigo segue RESOLVED")
                .hasSize(1);
    }

    // --- #119: SERVICE_STATUS dispara quando o eixo expira -------------------

    @Test
    void criterio119_service_status_dispara_quando_o_eixo_expira() throws Exception {
        Scene scene = newScene("e2e-status", "Org AlertEngine E2E Status");
        localMonitor(scene, "Monitor E2E status");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Servico degradado alem da janela");
        body.put("type", "SERVICE_STATUS");
        body.put("operator", "EQUAL");
        body.put("thresholdStatus", "DEGRADED");
        body.put("window", "FIVE_MINUTES");
        body.put("severity", "SEV2");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), body);
        responseStatus = 500;

        tickAt(T0);

        Service service = serviceOf(scene);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(service.getStatusChangedAt()).isEqualTo(T0);
        assertThat(alertsOf(scene.serviceId()))
                .as("eixo em 0 segundos: NO_DATA nunca abre alerta")
                .isEmpty();

        Instant firedAt = T0.plusSeconds(6 * 60);
        tickAt(firedAt);

        service = serviceOf(scene);
        assertThat(service.getStatusChangedAt())
                .as("status continuou DEGRADED: o eixo não é reescrito")
                .isEqualTo(T0);

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.SERVICE_STATUS);
        assertThat(alert.getRuleThresholdStatus()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV2);
        assertThat(alert.getTriggerValue())
                .as("SERVICE_STATUS não produz número")
                .isNull();
        assertThat(alert.getStartedAt()).isEqualTo(firedAt);
        assertThat(alert.getMessage())
                .contains("service status DEGRADED for 6 minutes");
    }

    // --- #120: agendamento segue DESLIGADO no perfil de teste ----------------

    @Test
    void criterio120_agendamento_permanece_desligado_no_perfil_de_teste() throws Exception {
        Scene scene = newScene("e2e-disabled", "Org AlertEngine E2E Disabled");
        String monitorId = localMonitor(scene, "Monitor E2E disabled");
        responseStatus = 200;
        long dispatchedBefore = monitoringDispatcher.dispatchedCount();

        monitoringScheduler.scheduledTick();
        monitoringScheduler.scheduledTick();

        assertThat(monitoringProperties.isEnabled())
                .as("application-test.yml mantém sentinel.monitoring.enabled=false")
                .isFalse();
        assertThat(monitoringDispatcher.dispatchedCount())
                .as("scheduledTick com o motor pausado não despacha nada")
                .isEqualTo(dispatchedBefore);
        assertThat(checksOf(monitorId))
                .as("sem tick() manual nenhuma checagem roda")
                .isEmpty();
    }
}
