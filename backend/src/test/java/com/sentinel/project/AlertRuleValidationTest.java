package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.sentinel.project.alert.model.AlertRule;

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
 * Entidade/validação das alert rules (Prompt 12, Seção 42 — critérios 1–9)
 * contra contexto real.
 *
 * <p>Cobre o contrato do POST: payload válido completo (201 com campos
 * server-owned e escopo do path), name em branco/trim/limite de 120
 * caracteres, {@code active} que nasce {@code true} e os quatro campos
 * obrigatórios (severity, window, type, operator) faltando — cada um responde
 * 400 no shape padrão. A matriz type × operator fica nos testes unitários
 * dedicados (21.2–21.6).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleValidationTest extends AbstractAlertRuleIntegrationTest {

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

    // --- POST: caminho feliz ------------------------------------------------

    @Test
    void createValidRule_returnsSaved() throws Exception {
        Scene scene = newScene("alert-rule-valid", "Org AlertRule Valid");
        Map<String, Object> body = serviceLevelRule("Disponibilidade do checkout");

        MvcResult result = mockMvc.perform(
                        postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(scene.org().organizationId()))
                .andExpect(jsonPath("$.projectId").value(scene.projectId()))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.name").value("Disponibilidade do checkout"))
                .andExpect(jsonPath("$.type").value("AVAILABILITY"))
                .andExpect(jsonPath("$.operator").value("LESS_THAN"))
                .andExpect(jsonPath("$.thresholdNumeric").value(99.9))
                .andExpect(jsonPath("$.window").value("FIFTEEN_MINUTES"))
                .andExpect(jsonPath("$.severity").value("SEV3"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        // Service-level: escopo de monitor nulo e thresholds mutuamente
        // exclusivos (numeric presente, status fora do payload)
        Map<String, Object> response = responseBody(result);
        assertThat(response.get("monitorId")).isNull();
        assertThat(response.get("thresholdStatus")).isNull();
        assertThat(response).doesNotContainKey("nameNormalized");

        AlertRule persisted =
                alertRuleOf(scene.serviceId(), (String) response.get("id"));
        assertThat(persisted.getNameNormalized())
                .isEqualTo("disponibilidade do checkout");
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getMonitor()).isNull();
        assertThat(persisted.getService().getId().toString()).isEqualTo(scene.serviceId());
    }

    @Test
    void createDefaultActive_isTrue() throws Exception {
        Scene scene = newScene("alert-rule-active-default", "Org AlertRule Active");
        String id = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra nasce ativa");

        mockMvc.perform(get(alertRuleUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId(), id))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // --- POST: validação de entrada (nunca 500) -----------------------------

    @Test
    void createWithBlankName_throws400() throws Exception {
        Scene scene = newScene("alert-rule-blank-name", "Org AlertRule Blank");
        Map<String, Object> body = serviceLevelRule("   ");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must not be blank"))
                .andExpect(jsonPath("$.path").exists());
    }

    @Test
    void createWithWhitespaceName_trimsAndSaves() throws Exception {
        Scene scene = newScene("alert-rule-trim", "Org AlertRule Trim");
        Map<String, Object> body = serviceLevelRule("   Regra com espaços   ");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Regra com espaços"));
    }

    @Test
    void createWithName121Chars_throws400And120Succeeds() throws Exception {
        Scene scene = newScene("alert-rule-name-limit", "Org AlertRule NameLimit");
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("x".repeat(120)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("x".repeat(120)));
    }

    // --- POST: campos obrigatórios (critérios 6–9) --------------------------

    @Test
    void createWithoutSeverity_throws400() throws Exception {
        Scene scene = newScene("alert-rule-no-severity", "Org AlertRule NoSeverity");
        Map<String, Object> body = serviceLevelRule("Sem severidade");
        body.remove("severity");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("severity: must not be null"));
    }

    @Test
    void createWithoutWindow_throws400() throws Exception {
        Scene scene = newScene("alert-rule-no-window", "Org AlertRule NoWindow");
        Map<String, Object> body = serviceLevelRule("Sem janela");
        body.remove("window");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("window: must not be null"));
    }

    @Test
    void createWithoutType_throws400() throws Exception {
        Scene scene = newScene("alert-rule-no-type", "Org AlertRule NoType");
        Map<String, Object> body = serviceLevelRule("Sem tipo");
        body.remove("type");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("type: must not be null"));
    }

    @Test
    void createWithoutOperator_throws400() throws Exception {
        Scene scene = newScene("alert-rule-no-operator", "Org AlertRule NoOperator");
        Map<String, Object> body = serviceLevelRule("Sem operador");
        body.remove("operator");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("operator: must not be null"));
    }
}
