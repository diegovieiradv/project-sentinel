package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertStatus;
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
 * Pais inativos congelam o estado (Prompt 13) — critérios 75–78: desligar o
 * SERVIÇO ou o PROJETO resolve tudo com {@code PARENT_INACTIVE} no MESMO
 * request (gancho das rotas), e o engine é a rede de segurança para a corrida
 * entre desativar e um check em voo — nunca avalia, nunca cria.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertParentInactiveTest extends AbstractAlertEngineIntegrationTest {

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

    // --- cenário: alerta aberto + janela que continua disparando -------------

    private record Scenario(Scene scene, String ruleId) {
    }

    private Scenario openAlert(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org AlertEngine Parent");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor pais");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra sob pais ativos");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .isEqualTo(AlertStatus.FIRING);
        return new Scenario(scene, ruleId);
    }

    private Alert reloadAlert(Scenario s) {
        return singleAlertOf(s.scene().serviceId());
    }

    // --- #75: desativação do SERVIÇO resolve no mesmo request ----------------

    @Test
    void criterio75_desativar_servico_resolve_alertas_abertos_com_parent_inactive()
            throws Exception {
        Scenario s = openAlert("alert-parent-service");
        Alert before = reloadAlert(s);

        changeActivation(
                s.scene().org().ownerBearer(),
                s.scene().org().organizationId(), s.scene().projectId(),
                s.scene().serviceId(), false);

        Alert after = reloadAlert(s);
        assertThat(after.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(after.getResolutionReason())
                .isEqualTo(AlertResolutionReason.PARENT_INACTIVE);
        assertThat(after.getResolvedAt()).isEqualTo(NOW);
        assertThat(after.getMessage())
                .as("mensagem do FIRING preservada")
                .isEqualTo(before.getMessage());
    }

    // --- #76: desativação do PROJETO resolve no mesmo request ----------------

    @Test
    void criterio76_desativar_projeto_resolve_alertas_abertos_com_parent_inactive()
            throws Exception {
        Scene scene = newScene("alert-parent-project", "Org AlertEngine ProjectParent");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor projeto");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra sob projeto ativo");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .isEqualTo(AlertStatus.FIRING);

        changeStatus(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), false);

        Alert after = singleAlertOf(scene.serviceId());
        assertThat(after.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(after.getResolutionReason())
                .isEqualTo(AlertResolutionReason.PARENT_INACTIVE);
        assertThat(after.getResolvedAt()).isEqualTo(NOW);
    }

    // --- #77: rede de segurança do engine (corrida em voo) -------------------

    @Test
    void criterio77_ciclo_em_servico_desligado_resolve_com_parent_inactive()
            throws Exception {
        Scenario s = openAlert("alert-parent-race");

        // desligar DIRETO no repositório (a corrida que o gancho não pegou)
        var service = serviceRepository
                .findById(java.util.UUID.fromString(s.scene().serviceId()))
                .orElseThrow();
        service.setActive(false);
        serviceRepository.save(service);

        evaluate(s.scene().serviceId());

        Alert after = reloadAlert(s);
        assertThat(after.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(after.getResolutionReason())
                .isEqualTo(AlertResolutionReason.PARENT_INACTIVE);
        assertThat(after.getResolvedAt()).isEqualTo(NOW);
    }

    // --- PERF-01 / PERF-02: resolução em massa -------------------------------

    /**
     * PERF-01 e PERF-02: a resolução em massa de UM serviço
     * ({@code resolveOpenForService}) percorre o caminho paginado novo
     * ({@code Page<Alert>} + {@code Pageable}) e precisa continuar
     * devolvendo <b>TODOS</b> os alertas abertos.
     *
     * <p>Duas regras disparando na mesma janela viram DOIS alertas
     * {@code FIRING}; ao desativar o serviço, nenhum pode ficar para trás —
     * perder o segundo por causa do corte de página seria um alerta que
     * nunca mais resolve (preso em {@code FIRING} até o próximo ciclo).</p>
     */
    @Test
    void criterio75_permite_resolver_todos_os_alertas_abertos_do_servico_de_uma_vez()
            throws Exception {
        Scene scene = newScene("alert-parent-bulk", "Org AlertEngine Bulk");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor lote");
        createAlertRule(
                bearer, scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra lote A");
        createAlertRule(
                bearer, scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra lote B");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        List<Alert> open = openAlertsOf(scene.serviceId());
        assertThat(open)
                .as("duas regras na mesma janela → dois alertas FIRING")
                .hasSize(2)
                .allMatch(alert -> alert.getStatus() == AlertStatus.FIRING);

        changeActivation(
                bearer,
                scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), false);

        List<Alert> after = alertsOf(scene.serviceId());
        assertThat(after)
                .as("nenhum alerta se perde no caminho paginado")
                .hasSize(2);
        assertThat(after)
                .allMatch(alert -> alert.getStatus() == AlertStatus.RESOLVED)
                .allMatch(alert -> alert.getResolutionReason()
                        == AlertResolutionReason.PARENT_INACTIVE)
                .allMatch(alert -> alert.getResolvedAt().equals(NOW));
    }

    // --- #78: estado congelado — nada novo é avaliado ------------------------

    @Test
    void criterio78_nenhum_alerta_novo_e_aberto_com_pai_inativo() throws Exception {
        Scene scene = newScene("alert-parent-frozen", "Org AlertEngine Frozen");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor congelado");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra em serviço ligado");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        // serviço sai de cena ANTES de qualquer alerta
        changeActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), false);

        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        List<Alert> all = alertsOf(scene.serviceId());
        assertThat(all)
                .as("janela dispararia, mas o estado está congelado: nada nasce")
                .isEmpty();
        // e nada foi sequer avaliado (sem alerta, sem toque)
        assertThat(openAlertsOf(scene.serviceId())).isEmpty();
    }
}
