package com.sentinel.project;

import static com.sentinel.organization.web.TenantInterceptor.ORGANIZATION_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
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
 * Ataques de IDOR / troca de tenant contra o módulo Monitors (Prompt 08) — a
 * cadeia Organization → Project → Service → Monitor é validada em TRÊS níveis
 * e nenhum deles pode ser contornado trocando ids no path.
 *
 * <p>Cenário canônico: Alpha (Alice=ADMIN, Bob=OPERATOR) com projeto
 * "Alpha Backend" e serviço "Alpha API" (monitor "Monitor Alpha"); Beta
 * (Bob=ADMIN, Alice=VIEWER) com projeto "Beta API" e serviço "Beta Service"
 * (monitor "Monitor Beta"); mais Charlie sem membership nenhum. A política de
 * anti-enumeração é fixa: para quem não é membro a resposta é SEMPRE o 404
 * genérico {@code "Resource not found"} — nunca 403 — e nenhuma escrita é
 * aplicada. Membro com papel insuficiente (VIEWER) cai no 403 genérico da
 * checagem de papel do service.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorIdorAdversarialTest extends AbstractMonitorIntegrationTest {

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
    private String bobEmail;
    private String projectInAlpha;
    private String projectInBeta;
    private String serviceInAlpha;
    private String serviceInBeta;
    private String monitorInAlpha;
    private String monitorInBeta;

    @BeforeEach
    void buildScenario() throws Exception {
        suffix = "-monidor-" + SCENARIO.incrementAndGet();
        String aliceEmail = "alice" + suffix + "@example.com";
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

        monitorInAlpha = createMonitor(
                aliceBearer, alphaId, projectInAlpha, serviceInAlpha, "Monitor Alpha" + suffix);
        monitorInBeta = createMonitor(
                bobBearer, betaId, projectInBeta, serviceInBeta, "Monitor Beta" + suffix);
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
    void nonMemberGets404OnEveryMonitorEndpointAndNothingIsWritten() throws Exception {
        String listUrl = monitorsUrl(alphaId, projectInAlpha, serviceInAlpha);
        String detailUrl = monitorUrl(alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha);

        expectGeneric404(mockMvc.perform(get(listUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

        expectGeneric404(mockMvc.perform(get(detailUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

        expectGeneric404(mockMvc.perform(postCsrf(listUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Invasão", "url", PUBLIC_URL)))));

        expectGeneric404(mockMvc.perform(patchCsrf(detailUrl)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Hackeado")))));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha))
                                .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // o cenário Alpha permanece intacto
        mockMvc.perform(get(detailUrl)
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Alpha" + suffix))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.active").value(true));
    }

    // --- IDOR por id cross-tenant --------------------------------------------

    @Test
    void monitorIdFromOneTenantIs404WhenReachedThroughAnotherTenantPath() throws Exception {
        // Bob é membro das DUAS organizações: o 404 vem do escopo do tenant e
        // do serviço da URL — nunca ler monitor alheio nem escrever nele
        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        alphaId, projectInAlpha, serviceInAlpha, monitorInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(monitorUrl(
                        alphaId, projectInAlpha, serviceInAlpha, monitorInBeta))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Apropriado")))));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, monitorInBeta))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // e o monitor Beta continua exatamente como estava
        mockMvc.perform(get(monitorUrl(betaId, projectInBeta, serviceInBeta, monitorInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Beta" + suffix))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL));
    }

    @Test
    void projectIdFromAnotherTenantInsideOwnOrganizationPathReturns404() throws Exception {
        // projeto Beta dentro do path da organização Alpha → o projeto não
        // resolve no tenant → 404 ANTES de qualquer leitura/escrita de monitor
        expectGeneric404(mockMvc.perform(get(monitorsUrl(alphaId, projectInBeta, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(postCsrf(monitorsUrl(alphaId, projectInBeta, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Criado no Projeto Errado", "url", PUBLIC_URL)))));

        // monitor Alpha alcançado via projeto Beta (também fora do tenant)
        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        alphaId, projectInBeta, serviceInAlpha, monitorInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        // enquanto a própria combinação resolve normalmente
        mockMvc.perform(get(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // nada foi criado no serviço Beta
        mockMvc.perform(get(monitorsUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Monitor Beta" + suffix));
    }

    @Test
    void alphaMonitorThroughBetaProjectInsideOwnTenantReturns404() throws Exception {
        // Bob (ADMIN em Beta) tenta alcançar o monitor Alpha colocando o id
        // dele dentro do projeto Beta — o lookup prova Service → Monitor e
        // não encontra → o MESMO 404 genérico
        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        betaId, projectInBeta, serviceInBeta, monitorInAlpha))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(monitorUrl(
                        betaId, projectInBeta, serviceInBeta, monitorInAlpha))
                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("url", "http://169.254.169.254/meta")))));

        // o monitor Alpha não foi tocado
        mockMvc.perform(get(monitorUrl(alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Alpha" + suffix))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL));
    }

    @Test
    void monitorFromSiblingServiceIs404ThroughSiblingPath() throws Exception {
        // mesmo projeto, serviço irmão: o monitor pertence ao serviço do path
        // e não é alcançável pelo irmão (unicidade e lookup são POR serviço)
        String siblingService = createService(
                aliceBearer, alphaId, projectInAlpha, "Alpha BFF" + suffix);
        String siblingMonitor = createMonitor(
                aliceBearer, alphaId, projectInAlpha, siblingService, "Monitor Irmao" + suffix);

        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        alphaId, projectInAlpha, siblingService, monitorInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(monitorUrl(
                        alphaId, projectInAlpha, siblingService, monitorInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Roubado")))));

        // o monitor irmão segue legível e o original não foi renomeado
        mockMvc.perform(get(monitorUrl(
                                alphaId, projectInAlpha, siblingService, siblingMonitor))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Irmao" + suffix));

        mockMvc.perform(get(monitorUrl(alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Alpha" + suffix));
    }

    @Test
    void randomMonitorIdReturns404WithoutWriting() throws Exception {
        String random = UUID.randomUUID().toString();

        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        alphaId, projectInAlpha, serviceInAlpha, random))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(patchCsrf(monitorUrl(
                        alphaId, projectInAlpha, serviceInAlpha, random))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Fantasma")))));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, random))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));
    }

    @Test
    void malformedMonitorIdReturns404Never500() throws Exception {
        // parse manual no controller: id fora do formato UUID → o MESMO 404
        // genérico (nunca 500 nem 400 de conversão de tipo)
        expectGeneric404(mockMvc.perform(get(monitorUrl(
                        alphaId, projectInAlpha, serviceInAlpha, "not-a-uuid"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, "not-a-uuid"))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));
    }

    // --- VIEWER: lê, mas não muta --------------------------------------------

    @Test
    void viewerCanReadButGets403OnEveryWriteEndpoint() throws Exception {
        // Alice é VIEWER em Beta: leitura liberada para qualquer papel,
        // mutação restrita a ADMIN/OPERATOR
        mockMvc.perform(get(monitorsUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(monitorUrl(betaId, projectInBeta, serviceInBeta, monitorInBeta))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Beta" + suffix));

        expectForbidden(mockMvc.perform(postCsrf(monitorsUrl(betaId, projectInBeta, serviceInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Create", "url", PUBLIC_URL)))));

        expectForbidden(mockMvc.perform(patchCsrf(monitorUrl(
                        betaId, projectInBeta, serviceInBeta, monitorInBeta))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Viewer Rename")))));

        expectForbidden(mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        betaId, projectInBeta, serviceInBeta, monitorInBeta))
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false)))));

        // nenhuma das três escritas aconteceu
        mockMvc.perform(get(monitorUrl(betaId, projectInBeta, serviceInBeta, monitorInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor Beta" + suffix))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get(monitorsUrl(betaId, projectInBeta, serviceInBeta))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- OPERATOR: mutação permitida (RBAC positivo) -------------------------

    @Test
    void operatorCanCreateUpdateAndActivateMonitorInOwnTenant() throws Exception {
        MvcResult created = mockMvc.perform(
                        postCsrf(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of(
                                        "name", "Operado Pelo Bob" + suffix, "url", PUBLIC_URL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        String createdId = (String) responseBody(created).get("id");

        mockMvc.perform(patchCsrf(monitorUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, createdId))
                        .header(HttpHeaders.AUTHORIZATION, bobBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "HEAD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("HEAD"));

        mockMvc.perform(
                        patchCsrf(monitorActivationUrl(
                                        alphaId, projectInAlpha, serviceInAlpha, createdId))
                                .header(HttpHeaders.AUTHORIZATION, bobBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- autenticação e CSRF -------------------------------------------------

    @Test
    void unauthenticatedGets401OnEveryMonitorEndpoint() throws Exception {
        String listUrl = monitorsUrl(alphaId, projectInAlpha, serviceInAlpha);
        String detailUrl = monitorUrl(alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha);
        String activationUrl = monitorActivationUrl(
                alphaId, projectInAlpha, serviceInAlpha, monitorInAlpha);

        mockMvc.perform(get(listUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get(detailUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(postCsrf(listUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Token", "url", PUBLIC_URL))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(patchCsrf(detailUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(patchCsrf(activationUrl)
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
        mockMvc.perform(post(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF", "url", PUBLIC_URL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // safe method (GET) não passa pelo CSRF: a única barreira é a auth
        mockMvc.perform(get(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedBearerWriteIsCsrfExemptByFramework() throws Exception {
        // o Spring Security isenta da proteção CSRF toda requisição que traz
        // Authorization: Bearer — o double-submit continua obrigatório para
        // requisições autenticadas por cookie
        mockMvc.perform(post(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha))
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem CSRF", "url", PUBLIC_URL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sem CSRF"));
    }

    // --- fronteira do tenant (header × path) ---------------------------------

    @Test
    void conflictingHeaderAndPathOrganizationIdsReturn404() throws Exception {
        expectGeneric404(mockMvc.perform(get(monitorsUrl(alphaId, projectInAlpha, serviceInAlpha))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .header(ORGANIZATION_ID_HEADER, betaId)));
    }

    @Test
    void malformedOrganizationIdReturns404OnMonitorRoutes() throws Exception {
        expectGeneric404(mockMvc.perform(get(
                                "/api/v1/organizations/not-a-uuid/projects/" + projectInAlpha
                                        + "/services/" + serviceInAlpha + "/monitors")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(get(
                                "/api/v1/organizations/" + alphaId + "/projects/not-a-uuid/services/"
                                        + serviceInAlpha + "/monitors")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));
    }
}
