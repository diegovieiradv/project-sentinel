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
 * Ciclo de vida das REGRAS (Prompt 13) — critérios 71–74: regra inativa não é
 * avaliada; desligar a regra com alerta aberto resolve com
 * {@code RULE_DEACTIVATED} (gancho da API E rede de segurança do engine);
 * religar volta a avaliar do zero.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertRuleActiveTest extends AbstractAlertEngineIntegrationTest {

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

    // --- cenário: regra + janela que DISPARA, pronta para os ciclos ----------

    private record Scenario(Scene scene, String ruleId, String monitorId) {
    }

    private Scenario scenarioWithFiringWindow(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org AlertEngine RuleActive");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor regras");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra com ciclo de vida");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        return new Scenario(scene, ruleId, monitorId);
    }

    // --- #71: regra inativa não é avaliada -----------------------------------

    @Test
    void criterio71_regra_inativa_nunca_e_avaliada() throws Exception {
        Scenario s = scenarioWithFiringWindow("alert-active-inactive");
        changeAlertRuleActivation(
                s.scene().org().ownerBearer(),
                s.scene().org().organizationId(), s.scene().projectId(),
                s.scene().serviceId(), s.ruleId(), false);

        evaluate(s.scene().serviceId());

        assertThat(alertsOf(s.scene().serviceId()))
                .as("janela dispararia, mas a regra está fora do catálogo ativo")
                .isEmpty();
    }

    // --- #72: engine resolve alerta de regra desligada (rede de segurança) ---

    @Test
    void criterio72_ciclo_resolve_alerta_aberto_de_regra_desligada_com_rule_deactivated()
            throws Exception {
        Scenario s = scenarioWithFiringWindow("alert-active-safety");
        evaluate(s.scene().serviceId());
        Alert alert = singleAlertOf(s.scene().serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);

        // desligar DIRETO no repositório (fora do gancho da API): é o engine
        // que tem de varrer — caminho de corrida entre desativar e check em voo
        var rule = alertRuleOf(s.scene().serviceId(), s.ruleId());
        rule.setActive(false);
        alertRuleRepository.save(rule);

        evaluate(s.scene().serviceId());

        Alert resolved = alertsOf(s.scene().serviceId()).stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst().orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.getResolutionReason())
                .isEqualTo(AlertResolutionReason.RULE_DEACTIVATED);
        assertThat(resolved.getResolvedAt()).isEqualTo(NOW);
        assertThat(resolved.getMessage())
                .as("a mensagem do FIRING sobrevive à desativação")
                .isEqualTo(alert.getMessage());
    }

    // --- #73: desativação via API resolve SEM esperar o próximo ciclo --------

    @Test
    void criterio73_desativacao_via_api_resolve_imediatamente_sem_novo_ciclo()
            throws Exception {
        Scenario s = scenarioWithFiringWindow("alert-active-hook");
        evaluate(s.scene().serviceId());
        Alert alert = singleAlertOf(s.scene().serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);

        changeAlertRuleActivation(
                s.scene().org().ownerBearer(),
                s.scene().org().organizationId(), s.scene().projectId(),
                s.scene().serviceId(), s.ruleId(), false);

        Alert resolved = alertsOf(s.scene().serviceId()).stream()
                .filter(a -> a.getId().equals(alert.getId()))
                .findFirst().orElseThrow();
        assertThat(resolved.getStatus())
                .as("o gancho da rota resolve no MESMO request — sem ciclos pendentes")
                .isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.getResolutionReason())
                .isEqualTo(AlertResolutionReason.RULE_DEACTIVATED);
        assertThat(resolved.getResolvedAt()).isEqualTo(NOW);
    }

    // --- #74: religar volta a avaliar do zero --------------------------------

    @Test
    void criterio74_reativada_a_regra_o_proximo_ciclo_dispara_de_novo()
            throws Exception {
        Scenario s = scenarioWithFiringWindow("alert-active-reenable");
        changeAlertRuleActivation(
                s.scene().org().ownerBearer(),
                s.scene().org().organizationId(), s.scene().projectId(),
                s.scene().serviceId(), s.ruleId(), false);
        evaluate(s.scene().serviceId());
        assertThat(alertsOf(s.scene().serviceId())).isEmpty();

        changeAlertRuleActivation(
                s.scene().org().ownerBearer(),
                s.scene().org().organizationId(), s.scene().projectId(),
                s.scene().serviceId(), s.ruleId(), true);
        evaluate(s.scene().serviceId());

        List<Alert> all = new ArrayList<>(alertsOf(s.scene().serviceId()));
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus())
                .as("a janela ainda dispara → UM NOVO alerta")
                .isEqualTo(AlertStatus.FIRING);
        assertThat(all.get(0).getAlertRule().getId()).hasToString(s.ruleId());
    }
}
