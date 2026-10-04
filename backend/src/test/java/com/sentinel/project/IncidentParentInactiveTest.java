package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.sentinel.project.incident.model.Incident;

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
 * Pais inativos CONGELAM o incidente (Prompt 14) — a leitura continua aberta
 * (a história é consultável depois do congelamento) e toda mutação (create,
 * patch, status, link/unlink) responde 409 na ordem certa:
 * {@code 409 PROJECT_INACTIVE} ANTES de {@code 409 SERVICE_INACTIVE}, sempre
 * depois do 404 da cadeia e nunca antes dele.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentParentInactiveTest extends AbstractIncidentIntegrationTest {

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

    // --- serviço inativo -----------------------------------------------------

    @Test
    void inactiveServiceRejectsEveryMutationWithServiceInactive409() throws Exception {
        Scene scene = newScene("incident-parent-service", "Org Incident ServiceParent");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Sob serviço ativo");

        changeActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(postCsrf(incidentsUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Depois do congelamento"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"))
                .andExpect(jsonPath("$.message")
                        .value("Cannot create or modify incidents in an inactive service"));

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Reescrito"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));

        mockMvc.perform(patchCsrf(incidentStatusUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "RESOLVED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));

        mockMvc.perform(putCsrf(incidentAlertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId,
                                        java.util.UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));

        mockMvc.perform(deleteCsrf(incidentAlertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId,
                                        java.util.UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));

        Incident frozen = incidentOf(scene.serviceId(), incidentId);
        assertThat(frozen.getTitle()).isEqualTo("Sob serviço ativo");
        assertThat(frozen.getStatus().name()).isEqualTo("OPEN");
        assertThat(incidentsOf(scene.serviceId())).hasSize(1);
    }

    // --- projeto inativo -----------------------------------------------------

    @Test
    void inactiveProjectRejectsMutationBeforeServiceCheck() throws Exception {
        Scene scene = newScene("incident-parent-project", "Org Incident ProjectParent");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Sob projeto ativo");

        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), false);

        mockMvc.perform(postCsrf(incidentsUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Depois"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"))
                .andExpect(jsonPath("$.message")
                        .value("Cannot create or modify incidents in an inactive project"));

        assertThat(incidentOf(scene.serviceId(), incidentId).getTitle())
                .isEqualTo("Sob projeto ativo");
    }

    @Test
    void projectInactiveWinsOverServiceInactiveForOrdering() throws Exception {
        Scene scene = newScene("incident-parent-both", "Org Incident BothParents");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Ambos inativos");

        // dois níveis desligados: a ordem das checagens decide o código
        changeActivation(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), false);
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), false);

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Reescrito"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));
    }

    // --- a leitura continua aberta ------------------------------------------

    @Test
    void readingStaysOpenWhenParentsAreInactive() throws Exception {
        Scene scene = newScene("incident-parent-read", "Org Incident ReadParent");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Histórico congelado");

        changeActivation(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), false);
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), false);

        mockMvc.perform(get(incidentsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(incidentUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Histórico congelado"));

        mockMvc.perform(get(incidentEventsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CREATED"));

        mockMvc.perform(get(incidentAlertsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- a cadeia continua valendo antes do 409 -----------------------------

    @Test
    void chainStillWinsOverInactiveParentsFor404() throws Exception {
        Scene scene = newScene("incident-parent-chain", "Org Incident ChainParent");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Cadeia");

        changeActivation(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), false);

        // 404 genérico de um id alheio NÃO vira 409 por causa do pai inativo
        mockMvc.perform(get(incidentUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), java.util.UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // o próprio incidente segue legível (leitura não depende de ativo)
        mockMvc.perform(get(incidentUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }
}
