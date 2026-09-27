package com.sentinel.project;

import static com.sentinel.organization.web.TenantInterceptor.ORGANIZATION_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.project.model.ServiceStatus;

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
 * Ataques de IDOR / troca de tenant contra o módulo Services (Prompt 07) — a
 * cadeia Organization → Project → Service é validada em DOIS níveis e nenhum
 * dos dois pode ser contornado trocando ids no path.
 *
 * <p>Cenário canônico: Alpha (Alice=ADMIN, Bob=OPERATOR) com projeto
 * "Alpha Backend" e serviço "Alpha API"; Beta (Bob=ADMIN, Alice=VIEWER) com
 * projeto "Beta API" e serviço "Beta Service"; mais Charlie sem membership
 * nenhum. A política de anti-enumeração é fixa: para quem não é membro a
 * resposta é SEMPRE o 404 genérico {@code "Resource not found"} — nunca 403 —
 * e nenhuma escrita é aplicada. Membro com papel insuficiente (VIEWER) cai no
 * 403 genérico da checagem de papel do service.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceIdorAdversarialTest extends AbstractServiceIntegrationTest {

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
    private String serviceInAlpha;
    private String serviceInBeta;

    @BeforeEach
    void buildScenario() throws Exception {
        suffix = "-svcidor-" + SCENARIO.incrementAndGet();
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

        projectInAlpha = createProject(aliceBearer, alphaId, "Alpha Backend" + suffix);
        projectInBeta = createProject(bobBearer, betaId, "Beta API" + suffix);

        serviceInAlpha = createService(aliceBearer, alphaId, projectInAlpha, "Alpha API" + suffix);
        serviceInBeta = createService(bobBearer, betaId, projectInBeta, "Beta Service" + suffix);
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
    void nonMemberGets404OnEveryServiceEndpointAndNothingIsWritten() throws Exception {
        String listUrl = servicesUrl(alphaId, projectInAlpha);
        String detailUrl = serviceUrl(alphaId, projectInAlpha, serviceInAlpha);

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

        expectGeneric404(mockMvc.perform(
                        patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, serviceInAlpha))
                                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // o cenário Alpha permanece intacto
        mockMvc.perform(get(detailUrl)
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha API" + suffix))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    // --- IDOR por id cross-tenant --------------------------------------------

    @Test
    void serviceIdFromOneTenantIs404WhenReachedThroughAnotherTenantPath() throws Exception {
        // Bob é membro das DUAS organizações: o 404 vem do escopo do tenant e
        // do projeto da URL — nunca ler serviço alheio nem escrever nele
        expectGeneric404(mockMvc.perform(get(serviceUrl(alphaId, projectInAlpha, serviceInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(serviceUrl(alphaId, projectInAlpha, serviceInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Apropriado")))));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, serviceInBeta))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // e o serviço Beta continua exatamente como estava
        mockMvc.perform(get(serviceUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta Service" + suffix))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void projectIdFromAnotherTenantInsideOwnOrganizationPathReturns404() throws Exception {
        // projeto Beta dentro do path da organização Alpha → o projeto não
        // resolve no tenant → 404 ANTES de qualquer leitura/escrita de serviço
        expectGeneric404(mockMvc.perform(get(servicesUrl(alphaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(postCsrf(servicesUrl(alphaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Criado no Projeto Errado")))));

        // serviceId Alpha alcançado via projeto Beta (também fora do tenant)
        expectGeneric404(mockMvc.perform(get(serviceUrl(alphaId, projectInBeta, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        // enquanto a própria combinação resolve normalmente
        mockMvc.perform(get(servicesUrl(alphaId, projectInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // nada foi criado no projeto Beta
        mockMvc.perform(get(servicesUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Beta Service" + suffix));
    }

    @Test
    void alphaServiceThroughBetaProjectInsideOwnTenantReturns404() throws Exception {
        // Bob (ADMIN em Beta) tenta alcançar o serviço Alpha colocando o id
        // dele dentro do projeto Beta — o lookup prova Project → Service e
        // não encontra → o MESMO 404 genérico
        expectGeneric404(mockMvc.perform(get(serviceUrl(betaId, projectInBeta, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(serviceUrl(betaId, projectInBeta, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("description", "roubada")))));

        // o serviço Alpha não foi tocado
        mockMvc.perform(get(serviceUrl(alphaId, projectInAlpha, serviceInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void randomServiceIdReturns404WithoutWriting() throws Exception {
        String random = UUID.randomUUID().toString();

        expectGeneric404(mockMvc.perform(get(serviceUrl(alphaId, projectInAlpha, random))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(serviceUrl(alphaId, projectInAlpha, random))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Fantasma")))));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, random))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));
    }

    @Test
    void malformedServiceIdReturns404Never500() throws Exception {
        // parse manual no controller: id fora do formato UUID → o MESMO 404
        // genérico (nunca 500 nem 400 de conversão de tipo)
        expectGeneric404(mockMvc.perform(get(serviceUrl(alphaId, projectInAlpha, "not-a-uuid"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, "not-a-uuid"))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));
    }

    // --- VIEWER: lê, mas não muta --------------------------------------------

    @Test
    void viewerCanReadButGets403OnEveryWriteEndpoint() throws Exception {
        // Alice é VIEWER em Beta: leitura liberada para qualquer papel,
        // mutação restrita a ADMIN/OPERATOR
        mockMvc.perform(get(servicesUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(serviceUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta Service" + suffix));

        expectForbidden(mockMvc.perform(postCsrf(servicesUrl(betaId, projectInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Create")))));

        expectForbidden(mockMvc.perform(patchCsrf(serviceUrl(betaId, projectInBeta, serviceInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Rename")))));

        expectForbidden(mockMvc.perform(
                        patchCsrf(serviceActivationUrl(betaId, projectInBeta, serviceInBeta))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // nenhuma das três escritas aconteceu
        mockMvc.perform(get(serviceUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta Service" + suffix))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        mockMvc.perform(get(servicesUrl(betaId, projectInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- OPERATOR: mutação permitida (RBAC positivo) -------------------------

    @Test
    void operatorCanCreateUpdateAndActivateServiceInOwnTenant() throws Exception {
        MvcResult created = mockMvc.perform(
                        postCsrf(servicesUrl(alphaId, projectInAlpha))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Operado Pelo Bob" + suffix))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        String createdId = (String) responseBody(created).get("id");

        mockMvc.perform(patchCsrf(serviceUrl(alphaId, projectInAlpha, createdId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "pelo operador"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("pelo operador"));

        mockMvc.perform(
                        patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, createdId))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value(ServiceStatus.UNKNOWN.name()));
    }

    // --- autenticação e CSRF -------------------------------------------------

    @Test
    void unauthenticatedGets401OnEveryServiceEndpoint() throws Exception {
        String listUrl = servicesUrl(alphaId, projectInAlpha);
        String detailUrl = serviceUrl(alphaId, projectInAlpha, serviceInAlpha);

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

        mockMvc.perform(patchCsrf(serviceActivationUrl(alphaId, projectInAlpha, serviceInAlpha))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void writeWithoutCsrfTokenGets403() throws Exception {
        // requisição estilo-navegador (cookie/session, SEM Bearer): o CsrfFilter
        // responde ANTES da autenticação → 403, nunca uma escrita "que passou
        // por engano"
        mockMvc.perform(post(servicesUrl(alphaId, projectInAlpha))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // safe method (GET) não passa pelo CSRF: a única barreira é a auth
        mockMvc.perform(get(servicesUrl(alphaId, projectInAlpha)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedBearerWriteIsCsrfExemptByFramework() throws Exception {
        // o Spring Security isenta da proteção CSRF toda requisição que traz
        // Authorization: Bearer — o double-submit continua obrigatório para
        // requisições autenticadas por cookie
        mockMvc.perform(post(servicesUrl(alphaId, projectInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sem CSRF"));
    }

    // --- fronteira do tenant (header × path) ---------------------------------

    @Test
    void conflictingHeaderAndPathOrganizationIdsReturn404() throws Exception {
        expectGeneric404(mockMvc.perform(get(servicesUrl(alphaId, projectInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .header(ORGANIZATION_ID_HEADER, betaId)));
    }

    @Test
    void malformedOrganizationIdReturns404OnServiceRoutes() throws Exception {
        expectGeneric404(mockMvc.perform(get(
                                "/api/v1/organizations/not-a-uuid/projects/" + projectInAlpha
                                        + "/services")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(get(
                                "/api/v1/organizations/" + alphaId + "/projects/not-a-uuid/services")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));
    }
}
