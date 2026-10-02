package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
 * Contrato HTTP das alert rules (Prompt 12, Seção 54 — critérios 81–90) contra
 * contexto real.
 *
 * <p>Shapes: página da listagem, detalhe completo, e o erro canônico
 * {@code {code, message, timestamp, path}} para TODOS os 400/405 — inclusive
 * JSON malformado, enum desconhecido e UUID inválido no path (que cai no
 * MESMO 404 anti-enumeração, nunca 500). DELETE não existe na rota → 405 no
 * shape padrão. Filtros e paginação exercitam a query real de listagem.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleApiTest extends AbstractAlertRuleIntegrationTest {

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

    // --- 81–85: verbos de sucesso -------------------------------------------

    @Test
    void postAlertRule_returns201() throws Exception {
        Scene scene = newScene("alert-api-post", "Org AlertRule ApiPost");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Criada via API"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Criada via API"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void getAlertRules_returnsPage() throws Exception {
        Scene scene = newScene("alert-api-page", "Org AlertRule ApiPage");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Única da página");

        mockMvc.perform(get(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].name").value("Única da página"));
    }

    @Test
    void getAlertRule_returns200() throws Exception {
        Scene scene = newScene("alert-api-detail", "Org AlertRule ApiDetail");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Detalhe completo");

        MvcResult result = mockMvc.perform(get(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ruleId))
                .andExpect(jsonPath("$.organizationId").value(scene.org().organizationId()))
                .andExpect(jsonPath("$.projectId").value(scene.projectId()))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.name").value("Detalhe completo"))
                .andExpect(jsonPath("$.type").value("AVAILABILITY"))
                .andExpect(jsonPath("$.operator").value("LESS_THAN"))
                .andExpect(jsonPath("$.thresholdNumeric").value(99.9))
                .andExpect(jsonPath("$.window").value("FIFTEEN_MINUTES"))
                .andExpect(jsonPath("$.severity").value("SEV3"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        // Service-level: monitorId existe no payload com valor null (não some)
        assertThat(responseBody(result).get("monitorId")).isNull();
        // nameNormalized é detalhe de implementação — fora do contrato
        assertThat(responseBody(result)).doesNotContainKey("nameNormalized");
    }

    @Test
    void patchAlertRule_returns200() throws Exception {
        Scene scene = newScene("alert-api-patch", "Org AlertRule ApiPatch");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Antes do patch");

        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("severity", "SEV1", "window", "ONE_HOUR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.window").value("ONE_HOUR"))
                .andExpect(jsonPath("$.name").value("Antes do patch"));
    }

    @Test
    void patchActivation_returns200() throws Exception {
        Scene scene = newScene("alert-api-activation", "Org AlertRule ApiActivation");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Ativação via API");

        mockMvc.perform(patchCsrf(alertRuleActivationUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.id").value(ruleId));
    }

    // --- 86: DELETE não existe ----------------------------------------------

    @Test
    void deleteAlertRule_returns405() throws Exception {
        Scene scene = newScene("alert-api-delete", "Org AlertRule ApiDelete");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Sem DELETE");

        mockMvc.perform(deleteCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"))
                .andExpect(jsonPath("$.path").isNotEmpty());

        // a regra continua viva
        assertThat(alertRuleOf(scene.serviceId(), ruleId)).isNotNull();
    }

    // --- 87–88: corpo inválido ----------------------------------------------

    @Test
    void postInvalidJson_returns400() throws Exception {
        Scene scene = newScene("alert-api-malformed", "Org AlertRule ApiMalformed");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"truncado\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());
    }

    @Test
    void postInvalidType_returns400() throws Exception {
        Scene scene = newScene("alert-api-enum", "Org AlertRule ApiEnum");

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Enum desconhecido",
                                "type", "EXPLOSION_LEVEL",
                                "operator", "LESS_THAN",
                                "thresholdNumeric", 99,
                                "window", "FIFTEEN_MINUTES",
                                "severity", "SEV3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());
    }

    // --- 89: UUID inválido no path ------------------------------------------

    @Test
    void getInvalidUuid_returns404() throws Exception {
        Scene scene = newScene("alert-api-uuid", "Org AlertRule ApiUuid");
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // path malformado NÃO vaza 500 nem detalhe: o MESMO 404 de não-existir
        mockMvc.perform(get(url + "/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- 90: shape do erro de validação -------------------------------------

    @Test
    void validationErrorShape_codeMessage() throws Exception {
        Scene scene = newScene("alert-api-shape", "Org AlertRule ApiShape");
        Map<String, Object> payload = serviceLevelRule("Sem type");
        payload.remove("type");

        MvcResult result = mockMvc.perform(postCsrf(alertRulesUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value(alertRulesUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId())))
                .andReturn();

        // os quatro campos do contrato — sempre presentes, nada além deles
        assertThat(responseBody(result))
                .containsOnlyKeys("code", "message", "timestamp", "path");
    }

    // --- extras: filtros e paginação da listagem ----------------------------

    @Test
    void listFiltersByTypeSeverityMonitorAndName() throws Exception {
        Scene scene = newScene("alert-api-filters", "Org AlertRule ApiFilters");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Monitor dos filtros");

        Map<String, Object> critical = serviceLevelRule("Disponibilidade crítica");
        critical.put("severity", "SEV1");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                critical);
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                monitorLevelRule("Latência do monitor", monitorId));
        Map<String, Object> failures = serviceLevelRule("Falhas do checkout");
        failures.put("type", "TARGET_FAILURE_COUNT");
        failures.put("operator", "GREATER_THAN");
        failures.put("thresholdNumeric", 3);
        failures.put("severity", "SEV2");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                failures);

        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?type=TARGET_FAILURE_COUNT")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Falhas do checkout"));

        mockMvc.perform(get(url + "?severity=SEV1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Disponibilidade crítica"));

        mockMvc.perform(get(url + "?monitorId=" + monitorId)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Latência do monitor"));

        // busca por nome: case-insensitive contra name_normalized (o LIKE real)
        mockMvc.perform(get(url + "?name=CRÍTICA")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Disponibilidade crítica"));

        // monitorId desconhecido → página vazia (filtro, não erro)
        mockMvc.perform(get(url + "?monitorId=" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidPaginationAndSortReturn400() throws Exception {
        Scene scene = newScene("alert-api-paging", "Org AlertRule ApiPaging");
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(url + "?size=101")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url + "?sort=hacked,desc")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("sort: must be one of createdAt, name, id"));
    }
}
