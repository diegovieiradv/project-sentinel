package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

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
 * NO_DATA e ERROR nunca decidem sozinhos (Prompt 13) — critérios 68–70:
 * janela sem evidência NÃO dispara, e NENHUM dos dois vereditos resolve um
 * alerta aberto (falta de dado não é "voltou ao normal"; falha de infra deixa
 * o próximo ciclo decidir).
 *
 * <p>O caminho de ERROR é induzido de propósito: a janela do monitor marca
 * como explosiva ({@link FlakyWindowMetricsConfig}) DEPOIS de o alerta estar
 * aberto — sem mockito e sem tocar em produção.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FixedMetricsClockConfig.class, FlakyWindowMetricsConfig.class})
class AlertNoDataTest extends AbstractAlertEngineIntegrationTest {

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

    // --- #68: janela vazia não dispara ---------------------------------------

    @Test
    void criterio68_janela_sem_checks_nao_dispara_alerta() throws Exception {
        Scene scene = newScene("alert-nodata-empty", "Org AlertEngine NoData");
        createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor sem checks");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra sem dado");

        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        assertThat(alertsOf(scene.serviceId()))
                .as("NO_DATA nunca cria alerta — janela vazia não é 0% nem falha")
                .isEmpty();
    }

    // --- #69: NO_DATA não resolve o alerta aberto ----------------------------

    @Test
    void criterio69_no_data_nao_resolve_alerta_aberto() throws Exception {
        Scene scene = newScene("alert-nodata-open", "Org AlertEngine NoDataOpen");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor que some");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra que fica");

        // dispara com falha na janela
        List<MonitorCheck> checks = new ArrayList<>();
        checks.addAll(seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1));
        checks.addAll(seedWindow(
                scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1));
        evaluate(scene.serviceId());
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        String messageBefore = alert.getMessage();

        // toda a janela some → NO_DATA (o histórico já observou a falha)
        monitorCheckRepository.deleteAll(checks);
        evaluate(scene.serviceId());

        Alert after = alertsOf(scene.serviceId()).stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst().orElseThrow();
        assertThat(after.getStatus())
                .as("falta de dado não pode apagar o alerta que sinaliza o problema")
                .isEqualTo(AlertStatus.FIRING);
        assertThat(after.getResolutionReason()).isNull();
        assertThat(after.getResolvedAt()).isNull();
        assertThat(after.getMessage()).isEqualTo(messageBefore);
    }

    // --- #70: ERROR não resolve o alerta aberto ------------------------------

    @Test
    void criterio70_error_de_infra_nao_resolve_alerta_aberto() throws Exception {
        Scene scene = newScene("alert-nodata-error", "Org AlertEngine Error");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor que explode");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(),
                monitorLevelRule("Regra monitor que explode", monitorId));
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        String messageBefore = alert.getMessage();

        // agora a fonte de métrica daquele monitor explode → ERROR
        FlakyWindowMetricsConfig.failingMonitorId = java.util.UUID.fromString(monitorId);
        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        Alert after = alertsOf(scene.serviceId()).stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst().orElseThrow();
        assertThat(after.getStatus())
                .as("falha de infra não dispara nem resolve — o próximo ciclo decide")
                .isEqualTo(AlertStatus.FIRING);
        assertThat(after.getResolutionReason()).isNull();
        assertThat(after.getResolvedAt()).isNull();
        assertThat(after.getMessage()).isEqualTo(messageBefore);
    }
}
