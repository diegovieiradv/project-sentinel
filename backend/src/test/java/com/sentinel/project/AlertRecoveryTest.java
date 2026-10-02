package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

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
 * Recuperação automática (Prompt 13) — critérios 62–67: quando a condição
 * volta ao normal o alerta aberto é resolvido com motivo AUTO, e a
 * resolução NUNCA toca na mensagem, no snapshot nem no início do FIRING (a
 * evidência permanece). Resolver sem alerta aberto é no-op seguro, e a
 * resolução de uma regra não mexe na outra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertRecoveryTest extends AbstractAlertEngineIntegrationTest {

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

    /**
     * Dispara o alerta e devolve (checks semeados, regra, monitor) para o
     * teste tornar a janela saudável depois.
     */
    private record Firing(Scene scene, String monitorId, List<MonitorCheck> checks) {
    }

    private Firing fireWithTargetFailure(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org AlertEngine Recovery");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor recuperação");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra que se recupera");
        List<MonitorCheck> checks = new ArrayList<>();
        checks.addAll(seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1));
        checks.addAll(seedWindow(
                scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1));
        evaluate(scene.serviceId());
        return new Firing(scene, monitorId, checks);
    }

    /** Remove a falha da janela → 100% → HEALTHY. */
    private void makeWindowHealthy(Firing firing) {
        monitorCheckRepository.deleteAll(firing.checks().stream()
                .filter(c -> c.getOutcome() == CheckOutcome.TARGET_FAILURE)
                .toList());
    }

    private Alert reload(Alert alert) {
        return alertsOf(alert.getService().getId().toString()).stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst()
                .orElseThrow();
    }

    // --- #62: HEALTHY resolve AUTO ------------------------------------------

    @Test
    void criterio62_condicao_volta_ao_normal_resolve_com_motivo_auto() throws Exception {
        Firing firing = fireWithTargetFailure("alert-recovery-auto");
        Alert alert = singleAlertOf(firing.scene().serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);

        makeWindowHealthy(firing);
        evaluate(firing.scene().serviceId());

        Alert resolved = reload(alert);
        assertThat(resolved.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.getResolutionReason()).isEqualTo(AlertResolutionReason.AUTO);
        assertThat(resolved.getResolvedAt())
                .as("instante do ciclo (relógio congelado)")
                .isEqualTo(NOW);
    }

    // --- #63: mensagem preservada -------------------------------------------

    @Test
    void criterio63_resolucao_auto_nunca_sobrescreve_a_mensagem_do_firing()
            throws Exception {
        Firing firing = fireWithTargetFailure("alert-recovery-message");
        Alert alert = singleAlertOf(firing.scene().serviceId());
        String firingMessage = alert.getMessage();
        assertThat(firingMessage)
                .as("a mensagem é a evidência do disparo")
                .startsWith("Regra que se recupera: availability 50.00%");

        makeWindowHealthy(firing);
        evaluate(firing.scene().serviceId());

        Alert resolved = reload(alert);
        assertThat(resolved.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.getMessage())
                .as("continua sendo a do FIRING — a resolução só grava motivo/instante")
                .isEqualTo(firingMessage);
        assertThat(resolved.getMessage())
                .as("NÃO vira frase de recuperação")
                .doesNotContain("healthy").doesNotContain("resolved");
    }

    // --- #64: snapshot e início preservados ----------------------------------

    @Test
    void criterio64_resolucao_preserva_snapshot_inicio_e_valor_gatilho()
            throws Exception {
        Firing firing = fireWithTargetFailure("alert-recovery-snapshot");
        Alert alert = singleAlertOf(firing.scene().serviceId());
        var severityBefore = alert.getSeverity();
        var thresholdBefore = alert.getRuleThresholdNumeric();
        var windowBefore = alert.getRuleWindow();
        var startedAtBefore = alert.getStartedAt();
        var triggerBefore = alert.getTriggerValue();

        makeWindowHealthy(firing);
        evaluate(firing.scene().serviceId());

        Alert resolved = reload(alert);
        assertThat(resolved.getSeverity()).isEqualTo(severityBefore);
        assertThat(resolved.getRuleThresholdNumeric()).isEqualByComparingTo(thresholdBefore);
        assertThat(resolved.getRuleWindow()).isEqualTo(windowBefore);
        assertThat(resolved.getStartedAt())
                .as("o início é imutável — o alerta durou de X a Y")
                .isEqualTo(startedAtBefore);
        assertThat(resolved.getTriggerValue())
                .as("o valor do disparo também é histórico")
                .isEqualByComparingTo(triggerBefore);
        assertThat(resolved.getLastEvaluatedAt()).isEqualTo(NOW);
    }

    // --- #65: uma regra não mexe na outra ------------------------------------

    @Test
    void criterio65_resolucao_de_uma_regra_nao_afeta_o_alerta_da_outra()
            throws Exception {
        Scene scene = newScene("alert-recovery-parallel", "Org AlertEngine Parallel");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor paralelo");
        // A: disponibilidade < 99.9% (dispara com 50% e continua com 33%)
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra A disponibilidade");
        // B: EXATAMENTE 1 target failure (dispara com 1, sara com 2)
        java.util.Map<String, Object> body = serviceLevelRule("Regra B contagem");
        body.put("type", "TARGET_FAILURE_COUNT");
        body.put("operator", "EQUAL");
        body.put("thresholdNumeric", 1);
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), body);

        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        assertThat(openAlertsOf(scene.serviceId()))
                .as("as duas regras disparam no ciclo inicial")
                .hasSize(2);

        // segunda falha: disponibilidade cai para 33% (A segue firmando),
        // mas a contagem vira 2 ≠ 1 → B resolve AUTO
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        // alertsOf usa JOIN FETCH da regra — leitura segura fora de sessão
        List<Alert> all = alertsOf(scene.serviceId());
        List<Alert> open = all.stream()
                .filter(a -> a.getStatus() == AlertStatus.FIRING).toList();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getAlertRule().getName())
                .as("A permanece aberta — a resolução de B não a alcançou")
                .isEqualTo("Regra A disponibilidade");
        List<Alert> resolved = all.stream()
                .filter(a -> a.getStatus() == AlertStatus.RESOLVED).toList();
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).getAlertRule().getName())
                .isEqualTo("Regra B contagem");
        assertThat(resolved.get(0).getResolutionReason())
                .isEqualTo(AlertResolutionReason.AUTO);
    }

    // --- #66: re-disparo é um NOVO alerta; o antigo fica intacto -------------

    @Test
    void criterio66_redisparo_apos_auto_abre_novo_alerta_e_o_antigo_permanece_resolvido()
            throws Exception {
        Firing firing = fireWithTargetFailure("alert-recovery-rescan");
        Alert first = singleAlertOf(firing.scene().serviceId());
        String firstMessage = first.getMessage();

        makeWindowHealthy(firing);
        evaluate(firing.scene().serviceId());
        // cai de novo (falha retorna à janela)
        seedWindow(
                firing.scene().serviceId(), firing.monitorId(),
                CheckOutcome.TARGET_FAILURE, 1);
        evaluate(firing.scene().serviceId());

        List<Alert> all = alertsOf(firing.scene().serviceId());
        assertThat(all).hasSize(2);
        Alert oldAlert = all.stream()
                .filter(a -> a.getId().equals(first.getId())).findFirst().orElseThrow();
        Alert newAlert = all.stream()
                .filter(a -> !a.getId().equals(first.getId())).findFirst().orElseThrow();
        assertThat(oldAlert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(oldAlert.getResolutionReason()).isEqualTo(AlertResolutionReason.AUTO);
        assertThat(oldAlert.getMessage()).isEqualTo(firstMessage);
        assertThat(newAlert.getStatus()).isEqualTo(AlertStatus.FIRING);
    }

    // --- #67: resolver sem alerta aberto é no-op seguro ----------------------

    @Test
    void criterio67_ciclo_saudavel_sem_alerta_aberto_nao_cria_nem_falha() throws Exception {
        Scene scene = newScene("alert-recovery-noop", "Org AlertEngine Noop");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor noop");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra saudável");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 3);

        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        assertThat(alertsOf(scene.serviceId()))
                .as("saudável e nunca disparou → nada a resolver, nada a criar")
                .isEmpty();
    }
}
