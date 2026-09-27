package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.model.ServiceStatus;

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
 * Endpoint de ativação ({@code PATCH .../services/{id}/activation}) —
 * soft-delete e idempotência (Prompt 07).
 *
 * <p>Contratos travados aqui: (1) ativar/desativar é <b>idempotente</b> —
 * repetir o estado atual devolve 200 SEM escrita, o {@code updatedAt} não
 * avança; (2) a rota mexe <b>somente</b> em {@code active} — o serviço nasce e
 * continua com {@code status=UNKNOWN}, inclusive com {@code active=false}
 * (estado perfeitamente válido, sem invocar semântica de monitoramento);
 * (3) entrada malformada, ausente ou nula é 400 com o shape padrão — nunca um
 * {@code false} implícito vindo de body ausente; (4) id desconhecido/malformado
 * é 404 genérico, nunca 500.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceActivationIdempotencyTest extends AbstractServiceIntegrationTest {

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
        Fixture org = newOrganization(uniqueEmail("svc-owner-roundtrip"), "Svc Roundtrip Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Ciclo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Ciclo de Vida");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Ciclo de Vida"));

        assertThat(serviceOf(projectId, serviceId).isActive()).isFalse();

        // soft-delete: o recurso continua legível e NÃO some da listagem
        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(serviceOf(projectId, serviceId).isActive()).isTrue();
    }

    @Test
    void repeatingCurrentStateReturns200WithoutTouchingUpdatedAt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-idempotent"), "Svc Idempotent Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Estável");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Estável Svc");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

        String updatedAtBefore = updatedAtOf(org, projectId, serviceId);

        // repetir o estado atual é um no-op: 200, mas SEM escrita — o
        // @PreUpdate não dispara e o timestamp não avança
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.updatedAt").value(updatedAtBefore));

        assertThat(updatedAtOf(org, projectId, serviceId)).isEqualTo(updatedAtBefore);

        // idem para o estado oposto repetido
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        String deactivationStamp = updatedAtOf(org, projectId, serviceId);
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(updatedAtOf(org, projectId, serviceId)).isEqualTo(deactivationStamp);
    }

    @Test
    void activationNeverTouchesStatusOrOtherFields() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-status-parity"), "Svc Status Parity");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Paridade");
        String serviceId = createService(
                org.ownerBearer(), org.organizationId(), projectId, "Intocável", "descrição original");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

        String createdAtBefore = String.valueOf(
                serviceOf(projectId, serviceId).getCreatedAt());

        // active=false + status=UNKNOWN é um estado válido: a rota de ativação
        // NÃO muda status (nem nome, nem descrição, nem createdAt)
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value(ServiceStatus.UNKNOWN.name()))
                .andExpect(jsonPath("$.name").value("Intocável"))
                .andExpect(jsonPath("$.description").value("descrição original"));

        com.sentinel.project.model.Service persisted = serviceOf(projectId, serviceId);
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(persisted.getNameNormalized()).isEqualTo("intocável");
        assertThat(String.valueOf(persisted.getCreatedAt())).isEqualTo(createdAtBefore);

        // e a rota de atualização também não consegue forçar status nem active
        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Intocável Renomeado",
                                "active", true,
                                "status", "UNAVAILABLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Intocável Renomeado"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value(ServiceStatus.UNKNOWN.name()));
    }

    @Test
    void activationWithMissingOrNullActiveReturns400NotNull() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-activation-null"), "Svc Activation Null");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Alvo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Alvo Svc");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

        // body vazio de objeto: Boolean null + @NotNull → o servidor NÃO
        // adivinha um "false" implícito
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        // {"active": null} explícito → o MESMO 400
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("active", null);
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(nullBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", ""))))
                .andExpect(status().isBadRequest());

        // e nada foi alterado
        assertThat(serviceOf(projectId, serviceId).isActive()).isTrue();
    }

    @Test
    void activationWithMalformedBodyReturns400Never500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-activation-malformed"), "Svc Activation Malformed");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Intocável");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Intocável 2");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

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

        assertThat(serviceOf(projectId, serviceId).isActive()).isTrue();
    }

    @Test
    void activationOfUnknownServiceReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-activation-404"), "Svc Activation 404");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "404");

        mockMvc.perform(patchCsrf(serviceActivationUrl(
                                org.organizationId(), projectId, UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void activationOfMalformedServiceIdReturns404Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-activation-malformed-id"), "Svc Activation Malformed Id");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "404b");

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- apoio ----------------------------------------------------------------

    private com.sentinel.project.model.Service serviceOf(String projectId, String serviceId) {
        return serviceRepository
                .findByProjectIdAndId(UUID.fromString(projectId), UUID.fromString(serviceId))
                .orElseThrow();
    }

    private String updatedAtOf(Fixture org, String projectId, String serviceId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get(serviceUrl(org.organizationId(), projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();
        Object updatedAt = responseBody(result).get("updatedAt");
        assertThat(updatedAt).isNotNull();
        return String.valueOf(updatedAt);
    }
}
