package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Matriz RBAC dos incidentes (Prompt 14) contra contexto real, com membros
 * reais por papel (owner ADMIN implícito + OPERATOR/VIEWER adicionados via API
 * de membros).
 *
 * <p>Leitura é livre para qualquer papel autenticado — inclusive timeline e
 * lista de alertas. Mutação (create/update/status/link/unlink) exige
 * ADMIN/OPERATOR e qualquer outro papel cai no MESMO 403 genérico
 * ("Access denied"). Anônimo → 401 antes de qualquer checagem de papel.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentRbacTest extends AbstractIncidentIntegrationTest {

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
        Scene scene = newScene("incident-rbac", "Org Incident RBAC");

        String operatorEmail = uniqueEmail("incident-operator");
        String operator = registerAndLogin("Operator Otto", operatorEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), operatorEmail, "OPERATOR");

        String viewerEmail = uniqueEmail("incident-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        return new Members(scene, operator, viewer);
    }

    private String url(Members members) {
        Scene scene = members.scene();
        return incidentsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());
    }

    // --- create --------------------------------------------------------------

    @Test
    void adminCreate_returns201() throws Exception {
        Members members = newMembersScene();

        mockMvc.perform(postCsrf(url(members))
                        .header(HttpHeaders.AUTHORIZATION, members.scene().org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Criado pelo admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void operatorCreate_returns201() throws Exception {
        Members members = newMembersScene();

        mockMvc.perform(postCsrf(url(members))
                        .header(HttpHeaders.AUTHORIZATION, members.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Criado pelo operator"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void viewerCreate_returns403() throws Exception {
        Members members = newMembersScene();

        mockMvc.perform(postCsrf(url(members))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Tentativa do viewer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        assertThat(incidentsOf(members.scene().serviceId()))
                .as("o 403 não escreve nada")
                .isEmpty();
    }

    // --- update / status / associação ---------------------------------------

    @Test
    void adminAndOperatorUpdate_return200AndViewerReturns403() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();

        String byAdmin = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Do admin");
        String byOperator = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Do operator");
        String blocked = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Bloqueado");

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), byAdmin))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Renomeada pelo admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renomeada pelo admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), byOperator))
                        .header(HttpHeaders.AUTHORIZATION, members.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("severity", "SEV1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), blocked))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Nome impossível"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // nada mudou para o viewer
        assertThat(incidentOf(scene.serviceId(), blocked).getTitle()).isEqualTo("Bloqueado");
    }

    @Test
    void viewerCannotChangeStatusOrLinkAlerts() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String incidentId = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Sob investigação");

        mockMvc.perform(patchCsrf(incidentStatusUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "INVESTIGATING"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(incidentOf(scene.serviceId(), incidentId).getStatus().name())
                .isEqualTo("OPEN");

        // unlink também é mutação
        mockMvc.perform(deleteCsrf(incidentAlertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId,
                                        java.util.UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- leitura -------------------------------------------------------------

    @Test
    void allRolesReadListDetailTimelineAndAlerts() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String incidentId = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Legível por todos");

        String list = incidentsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());
        String detail = incidentUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);
        String events = incidentEventsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);
        String alerts = incidentAlertsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);

        mockMvc.perform(get(list).header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"));

        mockMvc.perform(get(list).header(HttpHeaders.AUTHORIZATION, members.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("OPERATOR"));

        mockMvc.perform(get(list).header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(detail).header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mockMvc.perform(get(events).header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].message").value("Incident created"));

        mockMvc.perform(get(alerts).header(HttpHeaders.AUTHORIZATION, members.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void anonymousRequestsReturn401() throws Exception {
        Members members = newMembersScene();
        Scene scene = members.scene();
        String incidentId = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Anônimo fora");

        mockMvc.perform(get(incidentsUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(incidentUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                        incidentId)))
                .andExpect(status().isUnauthorized());
    }
}
