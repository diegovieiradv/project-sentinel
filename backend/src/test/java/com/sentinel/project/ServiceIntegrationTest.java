package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Módulo Services (Prompt 07) contra contexto real — segundo nível da
 * hierarquia Organization → Project → Service.
 *
 * <p>Cobertura: create (validações, trim, unicidade POR PROJETO em duas
 * camadas, limites de coluna, nascimento {@code active=true} +
 * {@code status=UNKNOWN}), list (paginação, ordenação determinística, filtros
 * {@code active}/{@code status}/{@code name}, escopo de projeto), get
 * (200/404 genérico), PATCH (parcial, rename com unicidade) e
 * {@code .../activation} (idempotente, sem tocar o status). Além disso:
 * massa de atribuição, projeto inativo ({@code 409 PROJECT_INACTIVE}), matriz
 * RBAC, stale auth, contrato de erro e os bloqueios 401/403/404/405. IDOR e
 * concorrência ficam nas classes próprias.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceIntegrationTest extends AbstractServiceIntegrationTest {

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
    void createReturns201WithServerOwnedFieldsAndTenantScopedContract() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-create"), "Svc Create Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Backend");

        MvcResult result = mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Payments API",
                                "description", "Gateway de pagamentos"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.description").value("Gateway de pagamentos"))
                .andExpect(jsonPath("$.active").value(true))
                // servidor define os dois: no catálogo e ainda não observado
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        Map<String, Object> body = responseBody(result);
        assertThat(body).doesNotContainKey("nameNormalized");

        com.sentinel.project.model.Service persisted = serviceOf(projectId, (String) body.get("id"));
        assertThat(persisted.getNameNormalized()).isEqualTo("payments api");
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(persisted.getProject().getId().toString()).isEqualTo(projectId);
        assertThat(persisted.getProject().getOrganization().getId().toString())
                .isEqualTo(org.organizationId());
    }

    @Test
    void createWithoutDescriptionPersistsNullDescription() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-no-desc"), "Svc No Desc Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Sem Desc");

        MvcResult result = mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Só Nome"))))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(responseBody(result).get("description")).isNull();
    }

    @Test
    void createTrimsNameAndPersistsTrimmedValue() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-trim"), "Svc Trim Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Trim");

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   Padded   "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Padded"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    // --- POST: validação de entrada (nunca 500) -----------------------------

    @Test
    void createWithBlankOrMissingNameReturns400NotBlank() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-blank"), "Svc Blank Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Alvo");
        String url = servicesUrl(org.organizationId(), projectId);

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must not be blank"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "sem nome"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must not be blank"));
    }

    @Test
    void createWithNameOver120CharsReturns400And120CharsSucceeds() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-len"), "Svc Len Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Tamanho");
        String url = servicesUrl(org.organizationId(), projectId);

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "y".repeat(120)))))
                .andExpect(status().isCreated());
    }

    @Test
    void createWithDescriptionOver1000CharsReturns400And1000Succeeds() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-dlen"), "Svc DLen Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Descrição");
        String url = servicesUrl(org.organizationId(), projectId);

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Com Desc", "description", "z".repeat(1001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("description: must be at most 1000 characters"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Com Desc Limite", "description", "z".repeat(1000)))))
                .andExpect(status().isCreated());
    }

    @Test
    void createWithMalformedJsonReturns400Never500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-malformed"), "Svc Malformed Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "JSON");

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createWithoutAuthenticationReturns401() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-noauth"), "Svc NoAuth Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Seguro");

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void createInProjectOfOrganizationUserIsNotMemberOfReturns404() throws Exception {
        Fixture foreign = newOrganization(uniqueEmail("svc-owner-foreign"), "Svc Foreign Corp");
        String foreignProject = createProject(foreign.ownerBearer(), foreign.organizationId(), "Privado");
        String stranger = registerAndLogin("Stranger Svc", uniqueEmail("stranger-svc"));
        createOrganization(stranger, "Stranger Svc Corp");

        mockMvc.perform(postCsrf(servicesUrl(foreign.organizationId(), foreignProject))
                        .header(HttpHeaders.AUTHORIZATION, stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Invasão"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void createInUnknownProjectReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-404"), "Svc 404 Corp");

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Órfão"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- POST: unicidade por projeto (duas camadas) -------------------------

    @Test
    void createDuplicateNameInSameProjectReturns409WithStableContract() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-dup"), "Svc Dup Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Pagamentos");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Payments API");

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  PAYMENTS API  "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Service with this name already exists in this project"));
    }

    @Test
    void createDuplicateOfDeactivatedServiceStillReturns409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-dup-off"), "Svc Dup Off Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Catálogo");
        String ghost = createService(org.ownerBearer(), org.organizationId(), projectId, "Ghost");
        changeActivation(org.ownerBearer(), org.organizationId(), projectId, ghost, false);

        // desativar NÃO libera o nome: o soft-delete mantém a unicidade
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ghost"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_NAME_DUPLICATE"));
    }

    @Test
    void createSameNameInDifferentProjectsReturns201ForBoth() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-same"), "Svc Same Corp");
        String first = createProject(org.ownerBearer(), org.organizationId(), "Projeto Um");
        String second = createProject(org.ownerBearer(), org.organizationId(), "Projeto Dois");

        // unicidade é POR projeto: o mesmo nome em projetos distintos é
        // cenário legítimo, não conflito
        createService(org.ownerBearer(), org.organizationId(), first, "Payments API");
        createService(org.ownerBearer(), org.organizationId(), second, "Payments API");

        mockMvc.perform(get(servicesUrl(org.organizationId(), second))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Payments API"));
    }

    @Test
    void createSameNameInDifferentOrganizationsReturns201ForBoth() throws Exception {
        Fixture orgA = newOrganization(uniqueEmail("svc-owner-same-a"), "Svc Same A Corp");
        Fixture orgB = newOrganization(uniqueEmail("svc-owner-same-b"), "Svc Same B Corp");
        String projectA = createProject(orgA.ownerBearer(), orgA.organizationId(), "Projeto");
        String projectB = createProject(orgB.ownerBearer(), orgB.organizationId(), "Projeto");

        createService(orgA.ownerBearer(), orgA.organizationId(), projectA, "Analytics");
        createService(orgB.ownerBearer(), orgB.organizationId(), projectB, "Analytics");

        mockMvc.perform(get(servicesUrl(orgB.organizationId(), projectB))
                        .header(HttpHeaders.AUTHORIZATION, orgB.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Analytics"));
    }

    // --- mass assignment ----------------------------------------------------

    @Test
    void createIgnoresServerOwnedFieldsSentInPayload() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-mass"), "Svc Mass Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Massa");
        String attackerId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Mass Assigned");
        payload.put("id", attackerId);
        payload.put("organizationId", "00000000-0000-0000-0000-0000000000ff");
        payload.put("projectId", "00000000-0000-0000-0000-0000000000ee");
        payload.put("active", false);
        payload.put("status", "OPERATIONAL");
        payload.put("createdAt", "2000-01-01T00:00:00Z");
        payload.put("updatedAt", "2000-01-01T00:00:00Z");
        payload.put("nameNormalized", "hacked");

        MvcResult result = mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andReturn();

        Map<String, Object> body = responseBody(result);
        assertThat(body.get("id")).isNotEqualTo(attackerId);
        assertThat(body.get("createdAt")).isNotEqualTo("2000-01-01T00:00:00Z");

        com.sentinel.project.model.Service persisted =
                serviceOf(projectId, (String) body.get("id"));
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(persisted.getNameNormalized()).isEqualTo("mass assigned");
        assertThat(persisted.getCreatedAt()).isAfter(java.time.Instant.parse("2001-01-01T00:00:00Z"));
    }

    @Test
    void updateIgnoresServerOwnedFieldsSentInPayload() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-mass-up"), "Svc Mass Up Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Massa PATCH");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Original");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Renomeado");
        payload.put("active", false);
        payload.put("status", "UNAVAILABLE");
        payload.put("projectId", "00000000-0000-0000-0000-0000000000ee");
        payload.put("id", UUID.randomUUID().toString());
        payload.put("updatedAt", "2000-01-01T00:00:00Z");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renomeado"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.projectId").value(projectId));

        com.sentinel.project.model.Service persisted = serviceOf(projectId, serviceId);
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(persisted.getDescription()).isNull();
    }

    // --- GET: detalhe -------------------------------------------------------

    @Test
    void getReturns200WithFullService() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-get"), "Svc Get Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Detalhe");
        String serviceId = createService(
                org.ownerBearer(), org.organizationId(), projectId, "Relatórios", "Diários");

        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.name").value("Relatórios"))
                .andExpect(jsonPath("$.description").value("Diários"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.nameNormalized").doesNotExist());
    }

    @Test
    void getUnknownServiceReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-get-404"), "Svc Get 404 Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Um");

        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getWithMalformedIdsReturns404Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-get-mal"), "Svc Get Mal Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mal");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Um");

        // serviceId malformado → 404 genérico (parse manual, nunca 500/400)
        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // projectId malformado → o mesmo 404
        mockMvc.perform(get(servicesUrl(org.organizationId(), "not-a-uuid") + "/" + serviceId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- GET: listagem ------------------------------------------------------

    @Test
    void listReturnsStablePagedShapeWithDeterministicOrder() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-list"), "Svc List Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Lista");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Alpha");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Beta");
        String third = createService(org.ownerBearer(), org.organizationId(), projectId, "Gama");

        MvcResult result = mockMvc.perform(get(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) responseBody(result).get("content");
        // padrão canônico: createdAt asc com id asc como desempate
        assertThat(content)
                .extracting(item -> item.get("id"))
                .containsExactly(first, second, third);
        assertThat(content).allSatisfy(item -> {
            assertThat(item.get("projectId")).isEqualTo(projectId);
            assertThat(item.get("organizationId")).isEqualTo(org.organizationId());
            assertThat(item.get("status")).isEqualTo("UNKNOWN");
            assertThat(item.get("nameNormalized")).isNull();
        });
    }

    @Test
    void listPaginatesWithSizeAndPage() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-page"), "Svc Page Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Página");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Três");

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?page=1&size=2")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listIsScopedToItsProjectOnly() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-scope"), "Svc Scope Corp");
        String first = createProject(org.ownerBearer(), org.organizationId(), "Escopo Um");
        String second = createProject(org.ownerBearer(), org.organizationId(), "Escopo Dois");
        createService(org.ownerBearer(), org.organizationId(), first, "Serviço A");
        createService(org.ownerBearer(), org.organizationId(), second, "Serviço B");

        // mesmo na MESMA organização, a listagem é por projeto — nunca um
        // agregado global de serviços
        mockMvc.perform(get(servicesUrl(org.organizationId(), first))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Serviço A"));

        mockMvc.perform(get(servicesUrl(org.organizationId(), second))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Serviço B"));
    }

    @Test
    void listFiltersByActiveFlag() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-filter"), "Svc Filter Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Filtros");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Ativo Um");
        String off = createService(org.ownerBearer(), org.organizationId(), projectId, "Desligado");
        changeActivation(org.ownerBearer(), org.organizationId(), projectId, off, false);

        // sem filtro: soft-delete NÃO some da listagem (a exclusão não existe)
        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?active=true")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Ativo Um"));

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?active=false")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Desligado"))
                .andExpect(jsonPath("$.content[0].active").value(false));
    }

    @Test
    void listFiltersByEveryStatusValueEvenWhenAllAreUnknown() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-status"), "Svc Status Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Statuses");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");

        // nenhum endpoint deste prompt grava status: todos nascem UNKNOWN.
        // O filtro aceita os QUATRO valores do enum — os três estados "de
        // saúde" simplesmente não têm representantes ainda
        for (ServiceStatus value : ServiceStatus.values()) {
            mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?status=" + value)
                            .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements")
                            .value(value == ServiceStatus.UNKNOWN ? 2 : 0));
        }

        // combinação de filtros: active × status × name
        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId)
                                + "?status=UNKNOWN&active=true&name=um")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Um"));
    }

    @Test
    void listWithInvalidStatusValueReturns400Never500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-badstatus"), "Svc Bad Status Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Query");

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?status=BOGUS")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    @Test
    void listSearchesByNameCaseInsensitiveAndSubstring() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-search"), "Svc Search Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Busca");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Payments API");
        createService(org.ownerBearer(), org.organizationId(), projectId, "payments worker");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Frontend App");

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?name=PAYMENTS")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Payments API"))
                .andExpect(jsonPath("$.content[1].name").value("payments worker"));
    }

    @Test
    void listSortsByNameDescending() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-sort"), "Svc Sort Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Ordena");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Aardvark");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Zebra");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Middle");

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId) + "?sort=name,desc")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Zebra"))
                .andExpect(jsonPath("$.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.content[2].name").value("Aardvark"));
    }

    @Test
    void listWithInvalidPaginationOrSortReturns400WithFieldMessage() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-page-bad"), "Svc Page Bad Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Inválido");
        String url = servicesUrl(org.organizationId(), projectId);

        mockMvc.perform(get(url + "?page=-1").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(url + "?size=0").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url + "?size=101").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url + "?size=100").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get(url + "?sort=password").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort: must be one of createdAt, name, id"));

        mockMvc.perform(get(url + "?sort=name,sideways")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort: must be one of createdAt, name, id"));

        mockMvc.perform(get(url + "?active=abc").header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    // --- PATCH .../services/{serviceId} -------------------------------------

    @Test
    void updateRenamesServiceAndPersistsNewNormalizedKey() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-rename"), "Svc Rename Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Rename");
        String serviceId = createService(
                org.ownerBearer(), org.organizationId(), projectId, "Nome Antigo", "desc");

        MvcResult result = mockMvc.perform(
                        patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Nome Novo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andReturn();

        assertThat(responseBody(result).get("updatedAt")).isNotNull();
        assertThat(serviceOf(projectId, serviceId).getNameNormalized()).isEqualTo("nome novo");
    }

    @Test
    void updateTouchesOnlyProvidedFields() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-partial"), "Svc Partial Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Parcial");
        String serviceId = createService(
                org.ownerBearer(), org.organizationId(), projectId, "Original", "descrição");

        // só description → nome, active, status, projeto e createdAt intactos
        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "nova descrição"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original"))
                .andExpect(jsonPath("$.description").value("nova descrição"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.projectId").value(projectId));

        com.sentinel.project.model.Service persisted = serviceOf(projectId, serviceId);
        assertThat(persisted.getName()).isEqualTo("Original");
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(persisted.getDescription()).isEqualTo("nova descrição");
    }

    @Test
    void updateWithEmptyDescriptionClearsIt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-clear"), "Svc Clear Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Limpa");
        String serviceId = createService(
                org.ownerBearer(), org.organizationId(), projectId, "Com Desc", "apagar");

        MvcResult result = mockMvc.perform(
                        patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("description", ""))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(responseBody(result).get("description")).isNull();
    }

    @Test
    void updateWithBlankOrOverlongNameReturns400AndAppliesNothing() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-patch-bad"), "Svc Patch Bad Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Alvo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Alvo");
        String url = serviceUrl(org.organizationId(), projectId, serviceId);

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "    "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must not be blank"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "d".repeat(1001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "description: must be at most 1000 characters"));

        // nada das três tentativas foi aplicado
        MvcResult untouched = mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alvo"))
                .andReturn();
        assertThat(responseBody(untouched).get("description")).isNull();
    }

    @Test
    void updateRenameToNameOfAnotherServiceReturns409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-rename-dup"), "Svc Rename Dup Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Duplicata");
        createService(org.ownerBearer(), org.organizationId(), projectId, "Ocupado");
        String free = createService(org.ownerBearer(), org.organizationId(), projectId, "Livre");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, free))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ocupado"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Service with this name already exists in this project"));

        // o rename NÃO foi aplicado
        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, free))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Livre"));
    }

    @Test
    void updateRenameToOwnNameIsIdempotent200() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-rename-self"), "Svc Rename Self Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mesmo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Mesmo Nome");

        // renomear para o próprio nome (ou só mudar a caixa) não pode colidir
        // consigo mesmo → 200, nunca 409
        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "MESMO NOME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MESMO NOME"));
    }

    @Test
    void updateServiceOfAnotherProjectReturns404() throws Exception {
        // o MESMO admin e a MESMA organização: o 404 vem do escopo do projeto
        Fixture org = newOrganization(uniqueEmail("svc-owner-cross"), "Svc Cross Corp");
        String first = createProject(org.ownerBearer(), org.organizationId(), "Projeto Alpha");
        String second = createProject(org.ownerBearer(), org.organizationId(), "Projeto Beta");
        createService(org.ownerBearer(), org.organizationId(), first, "Serviço Alpha");
        String betaService = createService(org.ownerBearer(), org.organizationId(), second, "Serviço Beta");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), first, betaService))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Apropriado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // e a escrita não aconteceu no serviço alvo
        assertThat(serviceOf(second, betaService).getName()).isEqualTo("Serviço Beta");
    }

    @Test
    void updateWithMalformedIdOrMissingBodyReturns404or400Never500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-patch-mal"), "Svc Patch Mal Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mal PATCH");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Intocável");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Qualquer"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{corpo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void updateWithoutAuthenticationReturns401() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-patch-noauth"), "Svc Patch NoAuth Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Seguro");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Seguro");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- ativação (idempotência detalhada na classe própria) ---------------

    @Test
    void activationTogglesActiveWithoutTouchingStatus() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-act"), "Svc Act Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Ativação");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Ciclo");

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                // desligar o cadastro NÃO diz nada sobre a saúde do serviço
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        com.sentinel.project.model.Service persisted = serviceOf(projectId, serviceId);
        assertThat(persisted.isActive()).isFalse();
        assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void activationWithMissingOrNullActiveReturns400NotNull() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-act-null"), "Svc Act Null Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Nulo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Alvo");
        String url = serviceActivationUrl(org.organizationId(), projectId, serviceId);

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
                        .content(json(nullActiveBody())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", ""))))
                .andExpect(status().isBadRequest());

        // corpo inexistente → 400 de entrada, nunca 500
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        assertThat(serviceOf(projectId, serviceId).isActive()).isTrue();
    }

    // --- RBAC ----------------------------------------------------------------

    @Test
    void viewerCanReadServicesButGets403OnEveryMutation() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-viewer"), "Svc Viewer Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Viewer Projeto");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Viewer Serviço");

        String viewerEmail = uniqueEmail("svc-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(org.ownerBearer(), org.organizationId(), viewerEmail, "VIEWER");

        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Viewer Create"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Viewer Rename"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // nenhuma das três escritas aconteceu
        com.sentinel.project.model.Service untouched = serviceOf(projectId, serviceId);
        assertThat(untouched.getName()).isEqualTo("Viewer Serviço");
        assertThat(untouched.isActive()).isTrue();
    }

    @Test
    void operatorCanCreateUpdateAndActivateServices() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-op"), "Svc Op Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Operador");

        String operatorEmail = uniqueEmail("svc-operator");
        String operator = registerAndLogin("Operator Otto", operatorEmail);
        addMember(org.ownerBearer(), org.organizationId(), operatorEmail, "OPERATOR");

        MvcResult created = mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Operado Pelo Otto"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        String serviceId = (String) responseBody(created).get("id");

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "pelo operador"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("pelo operador"));

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void sameTokenFollowsRoleChangeOnServiceEndpointsWithoutRefresh() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-stale"), "Svc Stale Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Stale");

        String bobEmail = uniqueEmail("svc-stale-bob");
        registerAndLogin("Stale Bob", bobEmail);
        addMember(org.ownerBearer(), org.organizationId(), bobEmail, "OPERATOR");
        String bob = bearer(loginOk(bobEmail));
        String bobMembershipId = membershipIdOf(bobEmail, org.organizationId());
        String url = servicesUrl(org.organizationId(), projectId);

        // OPERATOR: pode criar
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Antes"))))
                .andExpect(status().isCreated());

        // rebaixado para VIEWER → o MESMO token perde a mutação, sem refresh
        changeMemberRole(org.ownerBearer(), org.organizationId(), bobMembershipId, "VIEWER");
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Depois"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // promovido para ADMIN → o MESMO token volta a mutar
        changeMemberRole(org.ownerBearer(), org.organizationId(), bobMembershipId, "ADMIN");
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Depois"))))
                .andExpect(status().isCreated());
    }

    // --- projeto inativo ----------------------------------------------------

    @Test
    void inactiveProjectAllowsReadsAndBlocksMutationsWith409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-inactive"), "Svc Inactive Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Inativo");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Sob Inativo");
        changeStatus(org.ownerBearer(), org.organizationId(), projectId, false);

        // LEITURA continua liberada: o catálogo de um projeto inativo é
        // consultável (200), não um erro
        mockMvc.perform(get(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sob Inativo"));

        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // Mutações → 409 PROJECT_INACTIVE (projeto resolvido e inativo)
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"))
                .andExpect(jsonPath("$.message").value(
                        "Cannot create or modify services in an inactive project"));

        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renomeado Agora"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        mockMvc.perform(patchCsrf(serviceActivationUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // ordem documentada: projeto (404) → ativo (409) → serviço (404).
        // Projeto inativo com serviceId inexistente ainda responde 409
        mockMvc.perform(patchCsrf(serviceActivationUrl(
                                org.organizationId(), projectId, UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // nada foi aplicado
        com.sentinel.project.model.Service untouched = serviceOf(projectId, serviceId);
        assertThat(untouched.getName()).isEqualTo("Sob Inativo");
        assertThat(untouched.isActive()).isTrue();

        // reativar o projeto devolve o CRUD normal
        changeStatus(org.ownerBearer(), org.organizationId(), projectId, true);
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora"))))
                .andExpect(status().isCreated());
    }

    // --- ordem documentada das checagens (role → projeto → ativo → payload) --

    @Test
    void mutationOrderIsRoleThenProjectThenActiveThenPayload() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-order"), "Svc Order Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Ordem");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Ordem Svc");
        changeStatus(org.ownerBearer(), org.organizationId(), projectId, false);

        String viewerEmail = uniqueEmail("svc-order-viewer");
        String viewer = registerAndLogin("Viewer Ordem", viewerEmail);
        addMember(org.ownerBearer(), org.organizationId(), viewerEmail, "VIEWER");

        // (1) papel ANTES do projeto: VIEWER sobre projeto inexistente → 403,
        // nunca 404 (que confirmaria a existência do recurso)
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nunca Criado"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // (2) papel OK → projeto: ADMIN sobre projeto inexistente → 404
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nunca Criado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // (3) ativo ANTES do payload: no PATCH não há @Valid no controller — a
        // validação de nome acontece no service, DEPOIS da checagem do projeto
        // inativo → 409, nunca 400
        mockMvc.perform(patchCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // (4) ativo ANTES da unicidade: criação de nome duplicado em projeto
        // inativo → PROJECT_INACTIVE, nunca SERVICE_NAME_DUPLICATE
        mockMvc.perform(postCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ordem Svc"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // nada nasceu: o projeto inativo guarda exatamente o serviço original
        assertThat(serviceRepository.findByProjectId(UUID.fromString(projectId), PageRequest.of(0, 100)))
                .hasSize(1);
        assertThat(serviceOf(projectId, serviceId).getName()).isEqualTo("Ordem Svc");
        assertThat(projectRepository.findByOrganizationIdAndId(
                        UUID.fromString(org.organizationId()), UUID.fromString(projectId))
                .orElseThrow()
                .isActive())
                .isFalse();
    }

    // --- métodos não permitidos (405 no shape padrão) ------------------------

    @Test
    void deleteServiceReturns405BecauseRemovalDoesNotExistInThisPrompt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-delete"), "Svc Delete Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Permanente");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Permanente");

        mockMvc.perform(deleteCsrf(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"));

        // o serviço continua vivo: soft-delete é o .../activation, não DELETE
        mockMvc.perform(get(serviceUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void putOnServiceCollectionReturns405() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-owner-put"), "Svc Put Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "PUT");

        mockMvc.perform(putCsrf(servicesUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Substituir"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // --- apoio ----------------------------------------------------------------

    /** {@code Map.of} não aceita null — body {"active": null} precisa de mapa mutável. */
    private Map<String, Object> nullActiveBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", null);
        return body;
    }

    private com.sentinel.project.model.Service serviceOf(String projectId, String serviceId) {
        return serviceRepository
                .findByProjectIdAndId(UUID.fromString(projectId), UUID.fromString(serviceId))
                .orElseThrow();
    }

    private String membershipIdOf(String email, String organizationId) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return membershipRepository
                .findByUserIdAndOrganizationId(userId, UUID.fromString(organizationId))
                .orElseThrow()
                .getId()
                .toString();
    }

    private void changeMemberRole(
            String bearer, String organizationId, String membershipId, String role) throws Exception {
        mockMvc.perform(patchCsrf("/api/v1/organizations/" + organizationId + "/members/" + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", role))))
                .andExpect(status().isOk());
    }
}
