package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
 * Atualização parcial (Prompt 12, Seção 52 — critérios 69–76) contra contexto
 * real.
 *
 * <p>Campo ausente não altera; a condição EFETIVA (novo + persistido) é
 * revalidada pela matriz por completo. {@code monitorId} distingue os três
 * estados (ausente mantém, {@code null} limpa, valor revalida ownership).
 * Campos server-owned ({@code id}, {@code serviceId}, {@code active},
 * timestamps) e campos <b>desconhecidos</b> do contrato ({@code description},
 * {@code nameNormalized}, chaves inventadas) não existem no DTO e são
 * <b>IGNORADOS</b> se enviados — convenção fixada por
 * {@code patchIgnoresServerOwnedFields}, idêntica à de
 * {@code MonitorUpdateRequest}/{@code ServiceUpdateRequest} (Spring Boot
 * desabilita {@code FAIL_ON_UNKNOWN_PROPERTIES}).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRulePatchTest extends AbstractAlertRuleIntegrationTest {

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

    // --- 69–73: campos individuais ------------------------------------------

    @Test
    void patchName_updates() throws Exception {
        Scene scene = newScene("alert-patch-name", "Org Patch Name");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Nome antigo");

        patch(scene, ruleId, Map.of("name", "   Nome novo   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nome novo"));

        assertThat(alertRuleOf(scene.serviceId(), ruleId).getNameNormalized())
                .isEqualTo("nome novo");
    }

    @Test
    void patchThreshold_updates() throws Exception {
        Scene scene = newScene("alert-patch-threshold", "Org Patch Threshold");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Threshold editável");

        patch(scene, ruleId, Map.of("thresholdNumeric", 95.5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdNumeric").value(95.5));

        assertThat(alertRuleOf(scene.serviceId(), ruleId).getThresholdNumeric())
                .isEqualByComparingTo("95.5");
    }

    @Test
    void patchSeverity_updates() throws Exception {
        Scene scene = newScene("alert-patch-severity", "Org Patch Severity");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Severidade editável");

        patch(scene, ruleId, Map.of("severity", "SEV1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"));
    }

    @Test
    void patchWindow_updates() throws Exception {
        Scene scene = newScene("alert-patch-window", "Org Patch Window");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Janela editável");

        patch(scene, ruleId, Map.of("window", "ONE_HOUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("ONE_HOUR"));
    }

    @Test
    void patchOperator_updates() throws Exception {
        Scene scene = newScene("alert-patch-operator", "Org Patch Operator");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Operador editável");

        // LESS_THAN → LESS_THAN_OR_EQUAL: operador válido para AVAILABILITY
        patch(scene, ruleId, Map.of("operator", "LESS_THAN_OR_EQUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value("LESS_THAN_OR_EQUAL"));

        // operador INVÁLIDO para o tipo continua 400 (matriz no valor efetivo)
        patch(scene, ruleId, Map.of("operator", "GREATER_THAN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("operator: GREATER_THAN not allowed for AVAILABILITY"));
    }

    // --- 74: combinação inválida após mudança de tipo -----------------------

    @Test
    void patchTypeToServiceStatusWithNumeric_throws400() throws Exception {
        Scene scene = newScene("alert-patch-combo", "Org Patch Combo");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra numérica");

        // levar o número para SERVICE_STATUS é exatamente o erro #74 (o
        // operador vai junto: a revalidação é da condição efetiva COMPLETA)
        patch(scene, ruleId, Map.of("type", "SERVICE_STATUS", "operator", "EQUAL", "thresholdNumeric", 99))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("thresholdNumeric: must be null for SERVICE_STATUS"));

        // e a regra permanece intocada no estado anterior
        AlertRule persisted = alertRuleOf(scene.serviceId(), ruleId);
        assertThat(persisted.getType().name()).isEqualTo("AVAILABILITY");
        assertThat(persisted.getThresholdNumeric()).isEqualByComparingTo("99.9");
    }

    @Test
    void patchTypeToServiceStatusWithStatus_clearsNumericThreshold() throws Exception {
        Scene scene = newScene("alert-patch-convert-on", "Org Patch Convert On");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Conversão para status");

        // numérico → status: o número antigo é LIMPADO automaticamente
        // (sem exigir que cliente mande "thresholdNumeric": null); o operador
        // acompanha o tipo, senão a condição efetiva herdada seria inválida
        patch(scene, ruleId, Map.of("type", "SERVICE_STATUS", "operator", "EQUAL", "thresholdStatus", "UNAVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SERVICE_STATUS"))
                .andExpect(jsonPath("$.thresholdStatus").value("UNAVAILABLE"));

        AlertRule persisted = alertRuleOf(scene.serviceId(), ruleId);
        assertThat(persisted.getThresholdNumeric()).isNull();
        assertThat(persisted.getThresholdStatus().name()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void patchTypeToNumeric_clearsStatusThreshold() throws Exception {
        Scene scene = newScene("alert-patch-convert-off", "Org Patch Convert Off");
        Map<String, Object> statusBody = new LinkedHashMap<>();
        statusBody.put("name", "Regra de status");
        statusBody.put("type", "SERVICE_STATUS");
        statusBody.put("operator", "EQUAL");
        statusBody.put("thresholdStatus", "DEGRADED");
        statusBody.put("window", "FIFTEEN_MINUTES");
        statusBody.put("severity", "SEV3");
        String statusRule = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                statusBody);

        // status → numérico: o status antigo é LIMPADO (simétrico); o
        // operador acompanha o tipo (EQUAL não é válido para AVERAGE_LATENCY)
        patch(scene, statusRule, Map.of("type", "AVERAGE_LATENCY", "operator", "GREATER_THAN", "thresholdNumeric", 800))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("AVERAGE_LATENCY"))
                .andExpect(jsonPath("$.thresholdNumeric").value(800));

        AlertRule persisted = alertRuleOf(scene.serviceId(), statusRule);
        assertThat(persisted.getThresholdStatus()).isNull();
        assertThat(persisted.getThresholdNumeric()).isEqualByComparingTo("800");
    }

    // --- 75–76: server-owned --------------------------------------------------

    /**
     * Decisão ÚNICA e testada do contrato (critério #75): campos fora do
     * DTO — server-owned E desconhecidos — são <b>ignorados</b> (HTTP 200),
     * nunca 400. Espelha a convenção de {@code MonitorIntegrationTest} e
     * {@code ServiceIntegrationTest} (mass assignment).
     */
    @Test
    void patchIgnoresServerOwnedFields() throws Exception {
        Scene scene = newScene("alert-patch-owned-id", "Org Patch OwnedId");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Identidade protegida");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", java.util.UUID.randomUUID().toString());
        body.put("serviceId", java.util.UUID.randomUUID().toString());
        body.put("organizationId", java.util.UUID.randomUUID().toString());
        body.put("active", false);
        body.put("createdAt", "2020-01-01T00:00:00Z");
        body.put("updatedAt", "2020-01-01T00:00:00Z");
        // desconhecidos do contrato: description não existe mais no DTO;
        // nameNormalized e a chave inventada nunca existiram
        body.put("description", "fora do contrato");
        body.put("nameNormalized", "hacked");
        body.put("escalationPolicy", "PAGERDUTY");

        patch(scene, ruleId, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ruleId))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.nameNormalized").doesNotExist());

        AlertRule persisted = alertRuleOf(scene.serviceId(), ruleId);
        assertThat(persisted.getId().toString()).isEqualTo(ruleId);
        assertThat(persisted.getService().getId().toString()).isEqualTo(scene.serviceId());
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getNameNormalized()).isEqualTo("identidade protegida");
        assertThat(persisted.getCreatedAt()).isAfter(java.time.Instant.parse("2021-01-01T00:00:00Z"));
    }

    @Test
    void patchIgnoresServiceIdTransferAttempt() throws Exception {
        Scene scene = newScene("alert-patch-owned-scope", "Org Patch OwnedScope");
        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), "Serviço B");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Escopo imutável");

        patch(scene, ruleId, Map.of("serviceId", sibling, "name", "Tentativa de transferência"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.name").value("Tentativa de transferência"));

        // a regra continua no serviço original; o irmão não ganhou nada
        assertThat(alertRuleOf(scene.serviceId(), ruleId)).isNotNull();
        assertThat(alertRuleRepository.existsById(java.util.UUID.fromString(ruleId))).isTrue();
        mockMvc.perform(get(alertRulesUrl(scene.org().organizationId(), scene.projectId(), sibling))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- monitorId: os três estados ------------------------------------------

    @Test
    void patchWithoutMonitorId_keepsScope() throws Exception {
        Scene scene = newScene("alert-patch-monitor-keep", "Org Patch MonitorKeep");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Monitor do escopo");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                monitorLevelRule("Monitor-level estável", monitorId));

        // campo AUSENTE: severity muda, escopo permanece
        patch(scene, ruleId, Map.of("severity", "SEV2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorId").value(monitorId));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).getMonitor()).isNotNull();
    }

    @Test
    void patchWithNullMonitorId_clearsScope() throws Exception {
        Scene scene = newScene("alert-patch-monitor-clear", "Org Patch MonitorClear");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Monitor a soltar");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                monitorLevelRule("Monitor-level a limpar", monitorId));

        // "monitorId": null EXPLÍCITO: volta a Service-level
        Map<String, Object> clearScope = new LinkedHashMap<>();
        clearScope.put("monitorId", null);
        MvcResult cleared = patch(scene, ruleId, clearScope)
                .andExpect(status().isOk())
                .andReturn();

        // o campo continua no payload (serialização inclui nulls), com valor null
        assertThat(responseBody(cleared).get("monitorId")).isNull();
        AlertRule persisted = alertRuleOf(scene.serviceId(), ruleId);
        assertThat(persisted.getMonitor()).isNull();
        MvcResult reread = mockMvc.perform(get(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(responseBody(reread).get("monitorId")).isNull();
    }

    @Test
    void patchWithForeignMonitorId_returns404() throws Exception {
        Scene scene = newScene("alert-patch-monitor-foreign", "Org Patch MonitorForeign");
        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), "Serviço B");
        String foreignMonitor = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                sibling,
                "Monitor do irmão");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Service-level estável");

        patch(scene, ruleId, Map.of("monitorId", foreignMonitor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        assertThat(alertRuleOf(scene.serviceId(), ruleId).getMonitor()).isNull();
    }

    // --- apoio ---------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions patch(
            Scene scene, String ruleId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)));
    }
}
