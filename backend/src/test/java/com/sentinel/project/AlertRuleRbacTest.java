package com.sentinel.project;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Matriz RBAC das alert rules (Prompt 12, Seção 49 — critérios 49–56) contra
 * contexto real, com membros reais por papel (owner ADMIN implícito +
 * OPERATOR/VIEWER adicionados via API de membros).
 *
 * <p>Leitura é livre para qualquer papel autenticado; mutação (create/update)
 * exige ADMIN/OPERATOR e qualquer outro papel cai no MESMO 403 genérico
 * ("Access denied"). Anônimo → 401 antes de qualquer checagem de papel.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleRbacTest extends AbstractAlertRuleIntegrationTest {

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

    // --- membros por papel ---------------------------------------------------

    private record Members(Scene scene, String operator, String viewer) {
    }

    private Members newMembersScene() throws Exception {
        Scene scene = newScene("alert-rbac", "Org AlertRule RBAC");

        String operatorEmail = uniqueEmail("alert-operator");
        String operator = registerAndLogin("Operator Otto", operatorEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), operatorEmail, "OPERATOR");

        String viewerEmail = uniqueEmail("alert-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        return new Members(scene, operator, viewer);
    }

    // --- create --------------------------------------------------------------

    @Test
    void adminCreate_returns201() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Criada pelo admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void operatorCreate_returns201() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, members.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Criada pelo operator"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void viewerCreate_returns403() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();

        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Tentativa do viewer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // --- update --------------------------------------------------------------

    @Test
    void adminUpdate_returns200() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra do admin");

        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renomeada pelo admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renomeada pelo admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void operatorUpdate_returns200() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra do operator");

        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, members.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("severity", "SEV1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void viewerUpdate_returns403() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra bloqueada");

        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nome impossível"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // nada mudou no servidor
        mockMvc.perform(get(alertRuleUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Regra bloqueada"));
    }

    // --- leitura -------------------------------------------------------------

    @Test
    void allRolesList_returns200() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra legível");
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"));

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, members.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("OPERATOR"));

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));
    }

    @Test
    void anonymousList_returns401() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized());
    }
}
