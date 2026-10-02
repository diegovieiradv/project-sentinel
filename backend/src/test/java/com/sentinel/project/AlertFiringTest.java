package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.monitor.model.CheckOutcome;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Disparo do Alert Engine (Prompt 13) — critérios 51–56: o primeiro ciclo que
 * cumpre a condição CRIA o alerta com snapshot completo, mensagem
 * "regra: detalhe", valor gatilho e escopo corretos, SERVICE_STATUS dispara
 * sem número, e um ciclo multi-regra só abre alerta onde a condição vale.
 *
 * <p>Motor invocado DIRETO ({@code evaluateService}) com o relógio
 * congelado — o que valida aqui é a TRANSIÇÃO de estado (criação), não o
 * agendamento (é do E2E).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertFiringTest extends AbstractAlertEngineIntegrationTest {

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

    // --- #51: criação com snapshot completo ---------------------------------

    @Test
    void criterio51_primeiro_ciclo_que_cumpre_condicao_cria_alerta_firing_com_snapshot()
            throws Exception {
        Scene scene = newScene("alert-fire-basic", "Org AlertEngine Firing");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor básico");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Disponibilidade crítica");

        // 1 SUCCESS + 1 TARGET_FAILURE → 50.00% < 99.9% → FIRING
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getResolutionReason()).isNull();
        assertThat(alert.getResolvedAt()).isNull();
        assertThat(alert.getStartedAt()).isEqualTo(NOW);
        assertThat(alert.getLastEvaluatedAt()).isEqualTo(NOW);
        // snapshot da regra no instante do disparo (não há rule_severity extra)
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV3);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.AVAILABILITY);
        assertThat(alert.getRuleOperator())
                .isEqualTo(com.sentinel.project.alert.model.AlertOperator.LESS_THAN);
        assertThat(alert.getRuleThresholdNumeric()).isEqualByComparingTo("99.9");
        assertThat(alert.getRuleThresholdStatus()).isNull();
        assertThat(alert.getRuleWindow()).isEqualTo(EvaluationWindow.FIFTEEN_MINUTES);
        assertThat(alert.getAlertRule().getId()).hasToString(ruleId);
    }

    // --- #52: mensagem "regra: detalhe" --------------------------------------

    @Test
    void criterio52_mensagem_e_o_nome_da_regra_mais_o_detalhe_da_condicao()
            throws Exception {
        Scene scene = newScene("alert-fire-message", "Org AlertEngine Message");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor mensagem");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Disponibilidade crítica");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getMessage())
                .as("mensagem = nome da regra + ': ' + detalhe do avaliador")
                .isEqualTo("Disponibilidade crítica: "
                        + "availability 50.00% against threshold 99.90% over 15 minutes");
        assertThat(alert.getMessage().length()).isLessThanOrEqualTo(500);
        assertThat(alert.getMessage().trim()).isNotEmpty();
    }

    // --- #53: valor gatilho + escopo Service-level ---------------------------

    @Test
    void criterio53_valor_gatilho_e_monitor_nulo_em_service_level() throws Exception {
        Scene scene = newScene("alert-fire-trigger", "Org AlertEngine Trigger");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor trigger");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra service-level");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getTriggerValue())
                .as("50.00% observado no ciclo do disparo")
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(alert.getMonitorId())
                .as("Service-level não copia monitor")
                .isNull();
    }

    // --- #54: escopo de monitor copiado para o alerta ------------------------

    @Test
    void criterio54_alerta_de_regra_monitor_level_copia_o_monitor_id()
            throws Exception {
        Scene scene = newScene("alert-fire-scope", "Org AlertEngine Scope");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor com escopo");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(),
                monitorLevelRule("Latência do monitor", monitorId));
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getMonitorId()).hasToString(monitorId);
        assertThat(alert.getAlertRule().getId()).hasToString(ruleId);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.AVAILABILITY);
    }

    // --- #55: SERVICE_STATUS dispara sem número ------------------------------

    @Test
    void criterio55_service_status_dispara_com_trigger_value_nulo()
            throws Exception {
        Scene scene = newScene("alert-fire-status", "Org AlertEngine Status");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Indisponível há 15 minutos");
        body.put("type", "SERVICE_STATUS");
        body.put("operator", "EQUAL");
        body.put("thresholdStatus", "UNAVAILABLE");
        body.put("window", "FIFTEEN_MINUTES");
        body.put("severity", "SEV1");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), body);

        // UNAVAILABLE desde 12:00 (há 34 min ≥ janela de 15 min)
        forceServiceStatus(scene.serviceId(), "UNAVAILABLE", NOW.minusSeconds(2000));

        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.SERVICE_STATUS);
        assertThat(alert.getRuleThresholdStatus())
                .isEqualTo(com.sentinel.project.model.ServiceStatus.UNAVAILABLE);
        assertThat(alert.getTriggerValue())
                .as("SERVICE_STATUS não produz número")
                .isNull();
        assertThat(alert.getAlertRule().getId()).hasToString(ruleId);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV1);
    }

    // --- #56: ciclo multi-regra só dispara onde a condição vale --------------

    @Test
    void criterio56_ciclo_multi_regra_so_abre_alerta_onde_a_condicao_vale()
            throws Exception {
        Scene scene = newScene("alert-fire-multi", "Org AlertEngine Multi");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor multi");

        // regra A: < 99.9% → 50% dispara
        String ruleA = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra que dispara");
        // regra B: < 10% → 50% NÃO dispara
        Map<String, Object> neverFires = serviceLevelRule("Regra que não dispara");
        neverFires.put("thresholdNumeric", 10);
        String ruleB = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), neverFires);

        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        assertThat(openAlertsOf(scene.serviceId())).hasSize(1);
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getAlertRule().getId())
                .as("só a regra cuja condição vale tem alerta aberto")
                .hasToString(ruleA);
        assertThat(alertRuleOf(scene.serviceId(), ruleB)).isNotNull();
    }
}
