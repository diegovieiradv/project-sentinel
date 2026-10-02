package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Escopo de monitor das alert rules (Prompt 12, Seção 48 — critérios 43–48)
 * contra contexto real.
 *
 * <p>Service-level ({@code monitorId} nulo) e Monitor-level (monitor do
 * MESMO serviço) convivem; qualquer referência a um monitor alheio —
 * outro serviço, outro projeto, outro tenant ou id inexistente — responde
 * 404 genérico (nunca 403, nunca vazamento de existência).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleMonitorScopeTest extends AbstractAlertRuleIntegrationTest {

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
    void createServiceLevelRule_monitorIdNull() throws Exception {
        Scene scene = newScene("alert-scope-service", "Org Scope Service");

        MvcResult result = mockMvc.perform(postCsrf(alertRulesUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Service-level sem monitor"))))
                .andExpect(status().isCreated())
                .andReturn();

        String id = (String) responseBody(result).get("id");
        assertThat(responseBody(result).get("monitorId")).isNull();
        assertThat(alertRuleOf(scene.serviceId(), id).getMonitor()).isNull();
    }

    @Test
    void createMonitorLevelRule_validMonitor() throws Exception {
        Scene scene = newScene("alert-scope-monitor", "Org Scope Monitor");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Monitor alvo");

        Map<String, Object> body = monitorLevelRule("Monitor-level do mesmo serviço", monitorId);
        MvcResult result = mockMvc.perform(postCsrf(alertRulesUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monitorId").value(monitorId))
                .andReturn();

        String id = (String) responseBody(result).get("id");
        assertThat(alertRuleOf(scene.serviceId(), id).getMonitor().getId().toString())
                .isEqualTo(monitorId);
    }

    @Test
    void createWithMonitorFromOtherService_returns404() throws Exception {
        Scene scene = newScene("alert-scope-other-service", "Org Scope Sibling");
        String otherService = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), "Serviço Irmão");
        String foreignMonitor = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                otherService,
                "Monitor do irmão");

        expectGeneric404(scene, monitorLevelRule("Regra apontando irmão", foreignMonitor));
    }

    @Test
    void createWithMonitorFromOtherProject_returns404() throws Exception {
        Scene scene = newScene("alert-scope-other-project", "Org Scope Project");
        String otherProject = createProject(
                scene.org().ownerBearer(), scene.org().organizationId(), "Projeto Irmão");
        String otherService = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), otherProject, "Serviço do irmão");
        String foreignMonitor = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                otherProject,
                otherService,
                "Monitor de outro projeto");

        expectGeneric404(scene, monitorLevelRule("Regra apontando outro projeto", foreignMonitor));
    }

    @Test
    void createWithMonitorFromOtherTenant_returns404() throws Exception {
        Scene scene = newScene("alert-scope-tenant", "Org Scope Own");
        Fixture otherOrg = newOrganization(uniqueEmail("alert-scope-foreign"), "Org Scope Foreign");
        String otherProject = createProject(otherOrg.ownerBearer(), otherOrg.organizationId(), "Projeto deles");
        String otherService = createService(
                otherOrg.ownerBearer(), otherOrg.organizationId(), otherProject, "Serviço deles");
        String foreignMonitor = createMonitor(
                otherOrg.ownerBearer(),
                otherOrg.organizationId(),
                otherProject,
                otherService,
                "Monitor de outro tenant");

        expectGeneric404(scene, monitorLevelRule("Regra apontando outro tenant", foreignMonitor));
    }

    @Test
    void createWithNonexistentMonitor_returns404() throws Exception {
        Scene scene = newScene("alert-scope-unknown", "Org Scope Unknown");

        expectGeneric404(
                scene,
                monitorLevelRule("Regra apontando id fantasma", java.util.UUID.randomUUID().toString()));
    }

    // --- apoio ---------------------------------------------------------------

    /** POST com {@code monitorId} inválido → o MESMO 404 genérico, sem escrita. */
    private void expectGeneric404(Scene scene, Map<String, Object> body) throws Exception {
        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // leitura posterior prova que nada foi gravado
        mockMvc.perform(get(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
