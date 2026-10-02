package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.alerting.AlertEngine;
import com.sentinel.alerting.MonitoringAlertTrigger;
import com.sentinel.alerting.RuleEvaluator;
import com.sentinel.alerting.WindowMetrics;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.monitor.dto.MetricsSummary;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Isolamento de falhas do Alert Engine (Prompt 13) — critérios 79–83: serviço
 * sumido vira no-op; uma regra cuja métrica explode NÃO derruba o ciclo — a
 * regra irmã é avaliada normalmente; e o gatilho de produção NUNCA propaga
 * exceção para o motor de monitoramento (a liberação do in-flight na task
 * não pode depender do engine).
 *
 * <p>A explosão é induzida por {@link FlakyWindowMetricsConfig} (só o caminho
 * do monitor marcado) — sem mockito.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FixedMetricsClockConfig.class, FlakyWindowMetricsConfig.class})
class AlertFailureIsolationTest extends AbstractAlertEngineIntegrationTest {

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

    @AfterEach
    void resetFlakyFlag() {
        FlakyWindowMetricsConfig.failingMonitorId = null;
    }

    // --- #79: serviço inexistente é no-op ------------------------------------

    @Test
    void criterio79_ciclo_para_servico_inexistente_e_no_op_sem_excecao() {
        assertThatCode(() -> alertEngine.evaluateService(UUID.randomUUID()))
                .as("serviço sumido: log em debug e volta — nunca exceção")
                .doesNotThrowAnyException();
    }

    // --- #80: falha da métrica de UMA regra não corta o ciclo ----------------

    @Test
    void criterio80_falha_na_metrica_de_uma_regra_nao_derruba_o_ciclo() throws Exception {
        Scene scene = newScene("alert-iso-cycle", "Org AlertEngine Isolation");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor que explode");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra service que funciona");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(),
                monitorLevelRule("Regra monitor que explode", monitorId));
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        FlakyWindowMetricsConfig.failingMonitorId = java.util.UUID.fromString(monitorId);

        assertThatCode(() -> evaluate(scene.serviceId()))
                .as("o ERROR de uma regra é logado, não lançado")
                .doesNotThrowAnyException();

        assertThat(alertsOf(scene.serviceId()))
                .as("só a regra saudável tem alerta — a que explodiu não criou nem resolveu")
                .hasSize(1);
        assertThat(singleAlertOf(scene.serviceId()).getMonitorId())
                .as("o alerta é da regra Service-level (não explodiu)")
                .isNull();
    }

    // --- #81: ciclos repetidos com falha permanecem estáveis -----------------

    @Test
    void criterio81_ciclos_repetidos_com_falha_continuam_seguros_e_idempotentes()
            throws Exception {
        Scene scene = newScene("alert-iso-repeat", "Org AlertEngine IsolationRepeat");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor sempre explode");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra estável do serviço");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(),
                monitorLevelRule("Regra instável do monitor", monitorId));
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        FlakyWindowMetricsConfig.failingMonitorId = java.util.UUID.fromString(monitorId);

        assertThatCode(() -> {
            evaluate(scene.serviceId());
            evaluate(scene.serviceId());
            evaluate(scene.serviceId());
        }).doesNotThrowAnyException();

        assertThat(openAlertsOf(scene.serviceId()))
                .as("três ciclos → UM alerta (dedup intacta mesmo com erro vizinho)")
                .hasSize(1);
        assertThat(alertsOf(scene.serviceId())).hasSize(1);
    }

    // --- #82: o gatilho de produção nunca propaga ----------------------------

    @Test
    void criterio82_gatilho_de_producao_nunca_propaga_excecao() {
        // (a) motor explodindo (stand-in da falha de banco/transação)
        AlertEngine exploding = new AlertEngine(null, null, null, null, null, null) {
            @Override
            public void evaluateService(UUID serviceId) {
                throw new IllegalStateException("engine down (test)");
            }
        };
        MonitoringAlertTrigger trigger = new MonitoringAlertTrigger(exploding);
        Monitor persisted = monitorRepository.findAll().stream()
                .filter(m -> m.getService() != null)
                .findFirst()
                .orElse(null);

        if (persisted == null) {
            Scene scene;
            try {
                scene = newScene("alert-iso-trigger", "Org AlertEngine Trigger");
                String monitorId = createMonitor(
                        scene.org().ownerBearer(), scene.org().organizationId(),
                        scene.projectId(), scene.serviceId(), "Monitor do gatilho");
                persisted = monitorOf(scene.serviceId(), monitorId);
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        Monitor monitor = persisted;

        assertThatCode(() -> trigger.trigger(monitor))
                .as("falha do engine vira log com monitorId — nunca propaga")
                .doesNotThrowAnyException();

        // (b) monitor sem serviço → skip antes de tocar no engine
        Monitor orphan = new Monitor(
                null, "Órfão", PUBLIC_URL,
                com.sentinel.project.monitor.model.MonitorMethod.GET, 60, 5000);
        assertThatCode(() -> trigger.trigger(orphan))
                .doesNotThrowAnyException();
    }

    // --- #83: avaliador devolve ERROR, não exceção ---------------------------

    @Test
    void criterio83_avaliador_converte_falha_de_infra_em_veredito_error() {
        WindowMetrics exploding = new WindowMetrics() {
            @Override
            public MetricsSummary serviceWindow(UUID serviceId, Instant from, Instant to) {
                throw new IllegalStateException("metrics down (test)");
            }

            @Override
            public MetricsSummary monitorWindow(UUID monitorId, Instant from, Instant to) {
                throw new IllegalStateException("metrics down (test)");
            }
        };
        RuleEvaluator evaluator = new RuleEvaluator(exploding);
        AlertRule rule = new AlertRule(
                detachedService(), "Regra sob falha", null,
                AlertRuleType.AVAILABILITY, AlertOperator.LESS_THAN,
                threshold999(), null, EvaluationWindow.FIFTEEN_MINUTES,
                AlertSeverity.SEV3);

        assertThatCode(() -> {
            var result = evaluator.evaluate(rule, evaluationContext());
            assertThat(result.verdict())
                    .as("falha de infra vira ERROR — nunca exceção, nunca FIRING/HEALTHY")
                    .isEqualTo(com.sentinel.alerting.EvaluationResult.Verdict.ERROR);
            assertThat(result.value()).isNull();
        }).doesNotThrowAnyException();
    }

    // --- helpers -------------------------------------------------------------

    private static com.sentinel.project.model.Service detachedService() {
        var organization = new com.sentinel.organization.model.Organization("Iso Corp");
        var project = new com.sentinel.project.model.Project(organization, "api", "projeto");
        return new com.sentinel.project.model.Service(project, "checkout", "serviço");
    }

    private static java.math.BigDecimal threshold999() {
        return new java.math.BigDecimal("99.9");
    }

    private static com.sentinel.alerting.AlertEvaluationContext evaluationContext() {
        return new com.sentinel.alerting.AlertEvaluationContext(
                UUID.randomUUID(), null, null, FixedMetricsClockConfig.NOW);
    }
}
