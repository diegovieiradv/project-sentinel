package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * Módulo Projects (Prompt 06) contra contexto real — o primeiro recurso de
 * domínio enraizado no tenant.
 *
 * <p>Cobertura: create (validações, trim, unicidade por organização em duas
 * camadas, limites de coluna), list (paginação, ordenação determinística,
 * filtros de status e busca case-insensitive, isolamento por tenant), get
 * (200/404 genérico) e PATCH (parcial, rename com unicidade, limpeza de
 * descrição). Contrato de erro shape ({@code code}/{@code message}) e os
 * bloqueios de segurança (401/403/404/405) emendam aqui; IDOR, papel e
 * concorrência ficam nas classes próprias.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectIntegrationTest extends AbstractProjectIntegrationTest {

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

    // --- POST .../projects: caminho feliz -----------------------------------

    @Test
    void createReturns201WithTenantScopedContractAndNoInternalFields() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-create"), "Create Corp");
        MvcResult result = mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Backend API",
                                "description", "Servidor principal"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.name").value("Backend API"))
                .andExpect(jsonPath("$.description").value("Servidor principal"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                // papel de QUEM criou, não atributo do projeto
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        Map<String, Object> body = responseBody(result);
        // detalhe interno da unicidade nunca sai da API; id também não é nulo
        assertThat(body).doesNotContainKey("nameNormalized");
        assertThat(projectRepository.findById(java.util.UUID.fromString((String) body.get("id"))))
                .get()
                .satisfies(project -> {
                    assertThat(project.getNameNormalized()).isEqualTo("backend api");
                    assertThat(project.isActive()).isTrue();
                    assertThat(project.getOrganization().getId().toString())
                            .isEqualTo(org.organizationId());
                });
    }

    @Test
    void createWithoutDescriptionPersistsNullDescription() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-no-desc"), "No Desc Corp");
        MvcResult result = mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Só Nome"))))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(responseBody(result).get("description")).isNull();
    }

    @Test
    void createTrimsNameAndPersistsTrimmedValue() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-trim"), "Trim Corp");

        // o nome SEM espaços circundantes é o que persiste — o normalizado
        // deriva do valor já trimado
        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   Padded   "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Padded"));
    }

    // --- POST: validação de entrada (nunca 500) -----------------------------

    @Test
    void createWithBlankNameReturns400NotBlank() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-blank-name"), "Blank Name Corp");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must not be blank"));
    }

    @Test
    void createWithMissingNameReturns400NotBlank() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-missing-name"), "Missing Name Corp");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "sem nome"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must not be blank"));
    }

    @Test
    void createWithNameOver120CharsReturns400And120CharsSucceeds() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-name-length"), "Name Length Corp");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        // limite exato da coluna VARCHAR(120) é válido
        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "y".repeat(120)))))
                .andExpect(status().isCreated());
    }

    @Test
    void createWithDescriptionOver1000CharsReturns400And1000Succeeds() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-desc-length"), "Desc Length Corp");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Com Descrição Longa",
                                "description", "z".repeat(1001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "description: must be at most 1000 characters"));

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Com Descrição Limite",
                                "description", "z".repeat(1000)))))
                .andExpect(status().isCreated());
    }

    @Test
    void createWithoutAuthenticationReturns401() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-noauth-project"), "NoAuth Project Corp");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    // --- POST: unicidade por organização (duas camadas) ---------------------

    @Test
    void createDuplicateNameReturns409WithStableContract() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-dup"), "Dup Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Backend API");

        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "backend api"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Project with this name already exists in this organization"));
    }

    @Test
    void createDuplicateNameWithDifferentSpacingStillReturns409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-dup-space"), "Dup Space Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Frontend Web");

        // normalização é trim + lowercase (espaços INTERNOS são preservados):
        // só a caixa e os espaços circundantes decidem a unicidade
        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  FRONTEND WEB  "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"));
    }

    @Test
    void createDuplicateOfDeactivatedProjectStillReturns409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-dup-inactive"), "Dup Inactive Corp");
        String ghost = createProject(org.ownerBearer(), org.organizationId(), "Ghost");
        changeStatus(org.ownerBearer(), org.organizationId(), ghost, false);

        // desativar NÃO libera o nome: o soft-delete mantém a unicidade
        mockMvc.perform(postCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ghost"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"));
    }

    @Test
    void createSameNameInDifferentOrganizationsReturns201ForBoth() throws Exception {
        Fixture orgA = newOrganization(uniqueEmail("owner-same-a"), "Same Name A Corp");
        Fixture orgB = newOrganization(uniqueEmail("owner-same-b"), "Same Name B Corp");

        // unicidade é POR organização: nome repetido em tenants diferentes é
        // um cenário legítimo, não um conflito
        createProject(orgA.ownerBearer(), orgA.organizationId(), "Analytics");
        createProject(orgB.ownerBearer(), orgB.organizationId(), "Analytics");

        mockMvc.perform(get(projectsUrl(orgB.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, orgB.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Analytics"));
    }

    @Test
    void createInOrganizationUserIsNotMemberOfReturns404() throws Exception {
        Fixture foreign = newOrganization(uniqueEmail("owner-foreign-project"), "Foreign Project Corp");
        String stranger = registerAndLogin("Stranger", uniqueEmail("stranger-project"));
        createOrganization(stranger, "Stranger Project Corp");

        mockMvc.perform(postCsrf(projectsUrl(foreign.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Invasão"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- GET: detalhe -------------------------------------------------------

    @Test
    void getReturns200WithFullProject() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-get"), "Get Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Relatórios", "Diários");

        mockMvc.perform(get(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.name").value("Relatórios"))
                .andExpect(jsonPath("$.description").value("Diários"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.nameNormalized").doesNotExist());
    }

    @Test
    void getUnknownProjectReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-get-404"), "Get 404 Corp");

        mockMvc.perform(get(projectUrl(
                                org.organizationId(), java.util.UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getMalformedProjectIdReturns404Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-get-malformed"), "Get Malformed Corp");

        // parse manual no controller: id fora do formato UUID → o MESMO 404
        // genérico de projeto inexistente (nunca 500 nem 400)
        mockMvc.perform(get(projectUrl(org.organizationId(), "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getProjectOfOrganizationUserIsNotMemberOfReturns404() throws Exception {
        Fixture foreign = newOrganization(uniqueEmail("owner-get-foreign"), "Get Foreign Corp");
        String foreignProject = createProject(
                foreign.ownerBearer(), foreign.organizationId(), "Privado");
        String stranger = registerAndLogin("Stranger Get", uniqueEmail("stranger-get"));
        createOrganization(stranger, "Stranger Get Corp");

        // o estranho É admin da própria organização — o 404 prova que o
        // detalhe é escopado pelo tenant da URL, não pela posse de qualquer org
        mockMvc.perform(get(projectUrl(foreign.organizationId(), foreignProject))
                        .header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- GET: listagem ------------------------------------------------------

    @Test
    void listReturnsStablePagedShapeWithDeterministicOrder() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-list"), "List Project Corp");
        String first = createProject(org.ownerBearer(), org.organizationId(), "Alpha");
        String second = createProject(org.ownerBearer(), org.organizationId(), "Beta");
        String third = createProject(org.ownerBearer(), org.organizationId(), "Gama");

        MvcResult result = mockMvc.perform(get(projectsUrl(org.organizationId()))
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
        // padrão canônico: createdAt asc com id asc como desempate — sempre o
        // mesmo resultado, mesmo quando dois projetos compartilham timestamp
        assertThat(content)
                .extracting(item -> item.get("id"))
                .containsExactly(first, second, third);
        assertThat(content).allSatisfy(item -> {
            assertThat(item.get("organizationId")).isEqualTo(org.organizationId());
            assertThat(item.get("nameNormalized")).isNull();
        });
    }

    @Test
    void listPaginatesWithSizeAndPage() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-page"), "Page Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Um");
        createProject(org.ownerBearer(), org.organizationId(), "Dois");
        createProject(org.ownerBearer(), org.organizationId(), "Três");

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?page=1&size=2")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listFiltersByActiveStatusIncludingInactiveByDefault() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-filter"), "Filter Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Ativo Um");
        createProject(org.ownerBearer(), org.organizationId(), "Ativo Dois");
        String off = createProject(org.ownerBearer(), org.organizationId(), "Desligado");
        changeStatus(org.ownerBearer(), org.organizationId(), off, false);

        // sem filtro: soft-delete NÃO some da listagem (a exclusão não existe)
        mockMvc.perform(get(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?active=true")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].active").value(true));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?active=false")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Desligado"))
                .andExpect(jsonPath("$.content[0].active").value(false));
    }

    @Test
    void listSearchesByNameCaseInsensitiveAndSubstring() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-search"), "Search Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Backend API");
        createProject(org.ownerBearer(), org.organizationId(), "backend core");
        createProject(org.ownerBearer(), org.organizationId(), "Frontend App");

        // caixa diferente + subcadeia: a coluna normalizada torna a busca
        // case-insensitive sem LOWER() na query
        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?name=BACKEND")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Backend API"))
                .andExpect(jsonPath("$.content[1].name").value("backend core"));
    }

    @Test
    void listSortsByNameDescending() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-sort"), "Sort Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Aardvark");
        createProject(org.ownerBearer(), org.organizationId(), "Zebra");
        createProject(org.ownerBearer(), org.organizationId(), "Middle");

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?sort=name,desc")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Zebra"))
                .andExpect(jsonPath("$.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.content[2].name").value("Aardvark"));
    }

    @Test
    void listReturnsOnlyCallerOrganizationProjects() throws Exception {
        Fixture orgA = newOrganization(uniqueEmail("owner-scope-a"), "Scope A Corp");
        Fixture orgB = newOrganization(uniqueEmail("owner-scope-b"), "Scope B Corp");
        String bearerA = orgA.ownerBearer();
        String bearerB = orgB.ownerBearer();
        createProject(bearerA, orgA.organizationId(), "Projeto A");
        createProject(bearerB, orgB.organizationId(), "Projeto B");

        // mesmo que os dois donos sejam ADMIN, a listagem é POR tenant — nunca
        // um agregado global de projetos
        mockMvc.perform(get(projectsUrl(orgA.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Projeto A"));

        mockMvc.perform(get(projectsUrl(orgB.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Projeto B"));
    }

    // --- GET: paginação com entrada inválida (400, nunca 500) ---------------

    @Test
    void listWithInvalidPaginationOrSortReturns400WithFieldMessage() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-page-invalid"), "Page Invalid Corp");

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?size=0")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?size=101")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?sort=password")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "sort: must be one of createdAt, name, id"));

        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?sort=name,sideways")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "sort: must be one of createdAt, name, id"));

        // filtro enum não convertível → 400 do handler de tipo, nunca 500
        mockMvc.perform(get(projectsUrl(org.organizationId()) + "?active=abc")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    // --- PATCH .../projects/{projectId} -------------------------------------

    @Test
    void updateRenamesProjectAndPersistsNewNormalizedKey() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-rename"), "Rename Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Nome Antigo", "desc");

        MvcResult result = mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nome Novo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        assertThat(responseBody(result).get("updatedAt")).isNotNull();
        // a chave de unicidade acompanha o rename — sem isso a próxima criação
        // com o nome antigo passaria e a nova duplicaria em silêncio
        assertThat(projectRepository.findByOrganizationIdAndId(
                        java.util.UUID.fromString(org.organizationId()),
                        java.util.UUID.fromString(projectId))
                .orElseThrow()
                .getNameNormalized())
                .isEqualTo("nome novo");
    }

    @Test
    void updateWithAbsentFieldsKeepsCurrentValues() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-partial"), "Partial Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Original", "descrição");

        // PATCH parcial: só description → o nome permanece intocado
        mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "nova descrição"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original"))
                .andExpect(jsonPath("$.description").value("nova descrição"));
    }

    @Test
    void updateWithEmptyDescriptionClearsIt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-clear"), "Clear Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Com Desc", "apagar");

        // "" é um VALOR (limpa), diferente de campo ausente (preserva)
        MvcResult result = mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", ""))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(responseBody(result).get("description")).isNull();
    }

    @Test
    void updateWithBlankOrOverlongNameReturns400Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-patch-invalid"), "Patch Invalid Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Alvo");
        String url = projectUrl(org.organizationId(), projectId);

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "    "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
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
    void updateRenameToNameOfAnotherProjectReturns409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-rename-dup"), "Rename Dup Corp");
        createProject(org.ownerBearer(), org.organizationId(), "Ocupado");
        String free = createProject(org.ownerBearer(), org.organizationId(), "Livre");

        mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), free))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ocupado"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Project with this name already exists in this organization"));

        // o rename NÃO foi aplicado: o projeto "Livre" continua livre
        mockMvc.perform(get(projectUrl(org.organizationId(), free))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Livre"));
    }

    @Test
    void updateRenameToOwnNameIsIdempotent200() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-rename-self"), "Rename Self Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mesmo Nome");

        // renomear para o próprio nome (ou só mudar a caixa) não pode colidir
        // consigo mesmo → 200, nunca 409
        mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "MESMO NOME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MESMO NOME"));
    }

    @Test
    void updateProjectOfAnotherOrganizationReturns404() throws Exception {
        // o MESMO usuário administra as duas organizações: o 404 vem do escopo
        // do tenant, não da falta de permissão do chamador
        Fixture orgA = newOrganization(uniqueEmail("owner-cross-a"), "Cross A Corp");
        String bearer = orgA.ownerBearer();
        String orgB = createOrganization(bearer, "Cross B Corp");
        String projectInB = createProject(bearer, orgB, "Projeto B");

        mockMvc.perform(patchCsrf(projectUrl(orgA.organizationId(), projectInB))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Hackeado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // a escrita não aconteceu
        assertThat(projectRepository
                        .findByOrganizationIdAndId(
                                java.util.UUID.fromString(orgB),
                                java.util.UUID.fromString(projectInB))
                        .orElseThrow()
                        .getName())
                .isEqualTo("Projeto B");
    }

    @Test
    void updateMalformedProjectIdReturns404Not500() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-patch-malformed"), "Patch Malformed Corp");

        mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Qualquer"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void updateWithoutAuthenticationReturns401() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-patch-noauth"), "Patch NoAuth Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Seguro");

        mockMvc.perform(patchCsrf(projectUrl(org.organizationId(), projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- métodos não permitidos (405 no shape padrão) ------------------------

    @Test
    void deleteProjectReturns405BecauseRemovalDoesNotExistInThisPrompt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-delete"), "Delete Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Permanente");

        mockMvc.perform(deleteCsrf(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"));

        // o projeto continua vivo: soft-delete é o .../status, não DELETE
        mockMvc.perform(get(projectUrl(org.organizationId(), projectId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void putOnProjectCollectionReturns405() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-put"), "Put Corp");

        mockMvc.perform(putCsrf(projectsUrl(org.organizationId()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Substituir"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
