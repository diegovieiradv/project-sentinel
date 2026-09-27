package com.sentinel.project;

import static com.sentinel.organization.web.TenantInterceptor.ORGANIZATION_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Ataques de IDOR / troca de tenant contra o módulo Projects (Prompt 06).
 *
 * <p>Cenário canônico: Alpha (Alice=ADMIN, Bob=OPERATOR) e Beta (Bob=ADMIN,
 * Alice=VIEWER), mais Charlie sem membership nenhuma. A política de
 * anti-enumeração é fixa: para quem não é membro a resposta é SEMPRE o 404
 * genérico {@code "Resource not found"} — nunca 403 (que confirmaria a
 * existência do tenant) — e nenhuma escrita é aplicada. Membro com papel
 * insuficiente (VIEWER) cai no 403 genérico da checagem de papel do service.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectIdorAdversarialTest extends AbstractProjectIntegrationTest {

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

    /** Sufixo por teste: cadastro e organizações nunca colidem entre si. */
    private static final AtomicInteger SCENARIO = new AtomicInteger();

    private String suffix;
    private String alphaId;
    private String betaId;
    private String aliceBearer;
    private String bobBearer;
    private String charlieBearer;
    private String aliceEmail;
    private String bobEmail;
    private String projectInAlpha;
    private String projectInBeta;

    @BeforeEach
    void buildScenario() throws Exception {
        suffix = "-pjidor-" + SCENARIO.incrementAndGet();
        aliceEmail = "alice" + suffix + "@example.com";
        bobEmail = "bob" + suffix + "@example.com";
        String charlieEmail = "charlie" + suffix + "@example.com";

        aliceBearer = registerAndLogin("Alice Alpha", aliceEmail);
        bobBearer = registerAndLogin("Bob Beta", bobEmail);
        charlieBearer = registerAndLogin("Charlie NoTenant", charlieEmail);

        alphaId = createOrganization(aliceBearer, "Alpha" + suffix);
        addMember(aliceBearer, alphaId, bobEmail, "OPERATOR");

        betaId = createOrganization(bobBearer, "Beta" + suffix);
        addMember(bobBearer, betaId, aliceEmail, "VIEWER");

        projectInAlpha = createProject(aliceBearer, alphaId, "Alpha Project" + suffix);
        projectInBeta = createProject(bobBearer, betaId, "Beta Project" + suffix);
    }

    /** O 404 anti-enumeração, exatamente como o resto da API o devolve. */
    private void expectGeneric404(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        actions.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    /** O 403 da checagem de papel (membro, mas sem permissão de mutação). */
    private void expectForbidden(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // --- Charlie: sem membership em lugar nenhum -----------------------------

    @Test
    void nonMemberGets404OnEveryProjectEndpointAndNothingIsWritten() throws Exception {
        String listUrl = projectsUrl(alphaId);
        String detailUrl = projectUrl(alphaId, projectInAlpha);

        expectGeneric404(mockMvc.perform(get(listUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

        expectGeneric404(mockMvc.perform(get(detailUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

        expectGeneric404(mockMvc.perform(postCsrf(listUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Invasão")))));

        expectGeneric404(mockMvc.perform(patchCsrf(detailUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Hackeado")))));

        expectGeneric404(mockMvc.perform(patchCsrf(projectStatusUrl(alphaId, projectInAlpha))
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("active", false)))));

        // o cenário Alpha permanece intacto
        mockMvc.perform(get(detailUrl)
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha Project" + suffix))
                .andExpect(jsonPath("$.active").value(true));
    }

    // --- IDOR por id cross-tenant --------------------------------------------

    @Test
    void projectIdFromOneTenantIs404WhenReachedThroughAnotherTenantPath() throws Exception {
        // Bob é membro das DUAS organizações: o 404 vem do escopo do tenant da
        // URL, não da falta de permissão dele — nunca ler projeto alheio
        expectGeneric404(mockMvc.perform(get(projectUrl(alphaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(projectUrl(alphaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Apropriado")))));

        // enquanto o mesmo id, na própria organização, resolve normalmente
        mockMvc.perform(get(projectUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta Project" + suffix));
    }

    // --- VIEWER: lê, mas não muta --------------------------------------------

    @Test
    void viewerCanReadButGets403OnEveryWriteEndpoint() throws Exception {
        // Alice é VIEWER em Beta: leitura liberada para qualquer papel,
        // mutação restrita a ADMIN/OPERATOR
        mockMvc.perform(get(projectsUrl(betaId))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(projectUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        expectForbidden(mockMvc.perform(postCsrf(projectsUrl(betaId))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Create")))));

        expectForbidden(mockMvc.perform(patchCsrf(projectUrl(betaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Rename")))));

        expectForbidden(mockMvc.perform(patchCsrf(projectStatusUrl(betaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("active", false)))));

        // nenhuma das três escritas aconteceu
        mockMvc.perform(get(projectUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta Project" + suffix))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get(projectsUrl(betaId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- OPERATOR: mutação permitida (RBAC positivo) -------------------------

    @Test
    void operatorCanCreateUpdateAndChangeStatusInOwnTenant() throws Exception {
        // Bob é OPERATOR em Alpha — exatamente o papel de quem opera projetos
        MvcResult created = mockMvc.perform(postCsrf(projectsUrl(alphaId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Operado Pelo Bob"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        String projectId = (String) responseBody(created).get("id");

        mockMvc.perform(patchCsrf(projectUrl(alphaId, projectId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "pelo operador"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("pelo operador"));

        mockMvc.perform(patchCsrf(projectStatusUrl(alphaId, projectId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- autenticação e CSRF -------------------------------------------------

    @Test
    void unauthenticatedGets401OnEveryProjectEndpoint() throws Exception {
        String listUrl = projectsUrl(alphaId);
        String detailUrl = projectUrl(alphaId, projectInAlpha);

        mockMvc.perform(get(listUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get(detailUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(postCsrf(listUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(patchCsrf(detailUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void writeWithoutCsrfTokenGets403() throws Exception {
        // requisição estilo-navegador (cookie/session, SEM Bearer): o CsrfFilter
        // responde ANTES da autenticação → 403, nunca uma escrita "que passou
        // por engano"
        mockMvc.perform(post(projectsUrl(alphaId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // safe method (GET) não passa pelo CSRF: a única barreira é a auth
        mockMvc.perform(get(projectsUrl(alphaId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedBearerWriteIsCsrfExemptByFramework() throws Exception {
        // O Spring Security exclui da proteção CSRF toda requisição que traz
        // Authorization: Bearer (OAuth2ResourceServerConfigurer$
        // BearerTokenRequestMatcher) — o bearer não é anexado automaticamente
        // pelo navegador, logo não há superfície de CSRF. O double-submit
        // continua obrigatório para requisições autenticadas por cookie.
        mockMvc.perform(post(projectsUrl(alphaId))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sem CSRF"));
    }

    // --- fronteira do tenant (header × path) ---------------------------------

    @Test
    void conflictingHeaderAndPathOrganizationIdsReturn404() throws Exception {
        // path aponta para Alpha, header para Beta: possível confusão de tenant
        // → o MESMO 404 genérico, sem revelar qual dos dois existe
        expectGeneric404(mockMvc.perform(get(projectsUrl(alphaId))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .header(ORGANIZATION_ID_HEADER, betaId)));
    }

    @Test
    void malformedOrganizationIdReturns404OnProjectRoutes() throws Exception {
        // organizationId fora do formato UUID é tratado pelo interceptor como
        // organização inexistente — nunca 500
        expectGeneric404(mockMvc.perform(get("/api/v1/organizations/not-a-uuid/projects")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(get(
                                "/api/v1/organizations/not-a-uuid/projects/" + projectInAlpha)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));
    }
}
