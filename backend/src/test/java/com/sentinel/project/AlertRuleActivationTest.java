package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ativação/desativação das alert rules (Prompt 12, Seção 51 — critérios
 * 63–68) contra contexto real.
 *
 * <p>Regra nasce ativa; a rota {@code .../activation} é idempotente
 * ({@code no-op} sem escrita quando já no estado pedido) e fail-safe:
 * ATIVAR exige pais ativos (409), DESATIVAR é sempre permitido — inclusive
 * com pais inativos, porque desligar é a direção segura. Regra inativa
 * continua persistida e consultável ({@code active=false} na listagem).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleActivationTest extends AbstractAlertRuleIntegrationTest {

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

    @Test
    void createDefaultActiveTrue() throws Exception {
        Scene scene = newScene("alert-activation-birth", "Org AlertRule Birth");

        MvcResult result = mockMvc.perform(postCsrf(alertRulesUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Nasce ativa"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        String id = (String) responseBody(result).get("id");
        assertThat(alertRuleOf(scene.serviceId(), id).isActive()).isTrue();
    }

    @Test
    void deactivateRule_returnsActiveFalse() throws Exception {
        Scene scene = newScene("alert-activation-off", "Org AlertRule Off");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra desligada");

        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ruleId,
                false);

        mockMvc.perform(get(alertRuleUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).isActive()).isFalse();
    }

    @Test
    void reactivateRule_returnsActiveTrue() throws Exception {
        Scene scene = newScene("alert-activation-on", "Org AlertRule On");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra religada");

        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ruleId,
                false);
        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ruleId,
                true);

        mockMvc.perform(get(alertRuleUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).isActive()).isTrue();
    }

    @Test
    void activationIsIdempotentWithoutWrite() throws Exception {
        Scene scene = newScene("alert-activation-idem", "Org AlertRule Idem");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra idempotente");

        // já ativa → pedir ativar de novo: 200 no-op, updatedAt NÃO avança
        Instant before = alertRuleOf(scene.serviceId(), ruleId).getUpdatedAt();
        Thread.sleep(5); // relógio do banco tem granularidade fina
        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ruleId,
                true);

        assertThat(alertRuleOf(scene.serviceId(), ruleId).getUpdatedAt()).isEqualTo(before);
    }

    @Test
    void viewerActivate_returns403() throws Exception {
        Scene scene = newScene("alert-activation-viewer", "Org AlertRule ActViewer");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra do viewer");

        String viewerEmail = uniqueEmail("alert-act-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        mockMvc.perform(patchCsrf(alertRuleActivationUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        assertThat(alertRuleOf(scene.serviceId(), ruleId).isActive()).isTrue();
    }

    @Test
    void activateWithInactiveParent_returns409() throws Exception {
        Scene scene = newScene("alert-activation-parent", "Org AlertRule Parent");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra com pais");

        // serviço inativo → ativar exige o caminho seguro cheio de pais
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(patchCsrf(alertRuleActivationUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"))
                .andExpect(jsonPath("$.message")
                        .value("Cannot create or modify alert rules in an inactive service"));

        // projeto inativo → o 409 do projeto vem ANTES do do serviço
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), true);
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), false);

        mockMvc.perform(patchCsrf(alertRuleActivationUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));
    }

    @Test
    void deactivateWithInactiveParent_returns200() throws Exception {
        Scene scene = newScene("alert-activation-failsafe", "Org AlertRule Failsafe");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra fail-safe");

        // desliga o SERVIÇO com a regra ainda ativa — desligar a regra é a
        // direção segura e continua permitida (fail-safe)
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(patchCsrf(alertRuleActivationUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(alertRuleOf(scene.serviceId(), ruleId).isActive()).isFalse();
    }

    @Test
    void listWithActiveFalse_includesInactive() throws Exception {
        Scene scene = newScene("alert-activation-list", "Org AlertRule ActList");
        String keeper = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Mantida ativa");
        String paused = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Pausada");
        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                paused,
                false);

        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // sem filtro: inativa continua consultável (configuração, não execução)
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get(url + "?active=false")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Pausada"));

        mockMvc.perform(get(url + "?active=true")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Mantida ativa"));
    }
}
