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
 * Endpoint de status ({@code PATCH .../projects/{id}/status}) — soft-delete e
 * idempotência (Prompt 06).
 *
 * <p>Três contratos são travados aqui: (1) ativar/desativar é <b>idempotente</b>
 * — repetir o estado atual devolve 200 SEM escrita, o {@code updatedAt} não
 * avança; (2) inativar NÃO apaga: o projeto continua legível por GET e ainda
 * ocupa o nome na unicidade; (3) entrada malformada ou incompleta é 400 com o
 * shape padrão — nunca um {@code false} implícito vindo de body ausente.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectStatusIdempotencyTest extends AbstractProjectIntegrationTest {

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
    void deactivateAndReactivateRoundTripPersistsInDatabase() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-roundtrip"), "Status Roundtrip Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Ciclo de Vida");
        String url = projectStatusUrl(org.organizationId(), projectId);

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Ciclo de Vida"));

        assertThat(projectOf(org.organizationId(), projectId).isActive()).isFalse();

        // soft-delete: o recurso continua legível e NÃO some da listagem
        mockMvc.perform(get(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(projectOf(org.organizationId(), projectId).isActive()).isTrue();
    }

    @Test
    void repeatingCurrentStatusReturns200WithoutTouchingUpdatedAt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-idempotent"), "Status Idempotent Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Estável");
        String url = projectStatusUrl(org.organizationId(), projectId);

        String updatedAtBefore = updatedAtOf(org, projectId);

        // repetir o estado atual é um no-op: 200, mas SEM escrita — o
        // @PreUpdate não dispara e o timestamp não avança
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.updatedAt").value(updatedAtBefore));

        assertThat(updatedAtOf(org, projectId)).isEqualTo(updatedAtBefore);

        // idem para o estado oposto repetido
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        String deactivationStamp = updatedAtOf(org, projectId);
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(updatedAtOf(org, projectId)).isEqualTo(deactivationStamp);
    }

    @Test
    void statusWithMissingOrNullActiveReturns400NotNull() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-null"), "Status Null Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Alvo Status");
        String url = projectStatusUrl(org.organizationId(), projectId);

        // body vazio de objeto: Boolean null + @NotNull → o servidor NÃO
        // adivinha um "false" implícito
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", ""))))
                .andExpect(status().isBadRequest());

        // e nada foi alterado
        assertThat(projectOf(org.organizationId(), projectId).isActive()).isTrue();
    }

    @Test
    void statusWithMalformedBodyReturns400Never500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-malformed"), "Status Malformed Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Intocável");
        String url = projectStatusUrl(org.organizationId(), projectId);

        // corpo inexistente: JSON ilegível → 400 genérico de entrada
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        // tipo incompatível (objeto onde se espera boolean) → o mesmo 400 de
        // corpo ilegível, nunca 500 nem coerção silenciosa
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", Map.of("nested", true)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        assertThat(projectOf(org.organizationId(), projectId).isActive()).isTrue();
    }

    @Test
    void statusOfUnknownProjectReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-404"), "Status 404 Corp");

        mockMvc.perform(patchCsrf(projectStatusUrl(org.organizationId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void statusOfMalformedProjectIdReturns404Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-status-malformed-id"), "Status Malformed Id Corp");

        mockMvc.perform(patchCsrf(projectStatusUrl(org.organizationId(), "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- apoio ----------------------------------------------------------------

    private com.sentinel.project.model.Project projectOf(String organizationId, String projectId) {
        return projectRepository
                .findByOrganizationIdAndId(UUID.fromString(organizationId), UUID.fromString(projectId))
                .orElseThrow();
    }

    private String updatedAtOf(Fixture org, String projectId) throws Exception {
        MvcResult result = mockMvc.perform(get(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();
        Object updatedAt = responseBody(result).get("updatedAt");
        assertThat(updatedAt).isNotNull();
        return String.valueOf(updatedAt);
    }
}
