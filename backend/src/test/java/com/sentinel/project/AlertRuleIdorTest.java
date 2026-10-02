package com.sentinel.project;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Fronteira anti-IDOR das alert rules (Prompt 12, Seção 50 — critérios 57–62)
 * contra contexto real.
 *
 * <p>A cadeia Organization → Project → Service → AlertRule é resolvida com
 * consultas tenant-scoped: qualquer pedaço alheio (org, projeto, serviço ou
 * regra) responde o MESMO 404 genérico — em leitura E em escrita — sem
 * detalhar a existência do recurso e sem gravar nada.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleIdorTest extends AbstractAlertRuleIntegrationTest {

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

    // --- cenário: dois tenants, dois serviços, uma regra cada ----------------

    private record Pair(Scene alpha, String alphaRule, Scene beta, String betaRule) {
    }

    private Pair buildPair() throws Exception {
        Scene alpha = newScene("alert-idor-alpha", "Org AlertRule Alpha");
        String alphaRule = createAlertRule(
                alpha.org().ownerBearer(),
                alpha.org().organizationId(),
                alpha.projectId(),
                alpha.serviceId(),
                "Regra Alpha");

        Scene beta = newScene("alert-idor-beta", "Org AlertRule Beta");
        String betaRule = createAlertRule(
                beta.org().ownerBearer(),
                beta.org().organizationId(),
                beta.projectId(),
                beta.serviceId(),
                "Regra Beta");

        return new Pair(alpha, alphaRule, beta, betaRule);
    }

    // --- 57: regra de outro tenant ------------------------------------------

    @Test
    void ruleFromOtherOrg_returns404() throws Exception {
        Pair pair = buildPair();

        // caminho do PRÓPRIO tenant Beta apontando a regra de Alpha → 404
        expect404Read(pair.beta(), pair.betaRule(), pair.alphaRule());

        // caminho de Alpha com usuário de Beta (não-membro) → 404 do interceptor
        mockMvc.perform(get(alertRuleUrl(
                                        pair.alpha().org().organizationId(),
                                        pair.alpha().projectId(),
                                        pair.alpha().serviceId(),
                                        pair.alphaRule()))
                        .header(HttpHeaders.AUTHORIZATION, pair.beta().org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- 58: projeto de outro tenant no caminho próprio ----------------------

    @Test
    void projectIdFromAnotherTenantInsideOwnOrganizationPathReturns404() throws Exception {
        Pair pair = buildPair();

        // projeto de Alpha dentro do path de Beta (org Beta) → cadeia não existe
        String forged = "/api/v1/organizations/"
                + pair.beta().org().organizationId()
                + "/projects/"
                + pair.alpha().projectId()
                + "/services/"
                + pair.beta().serviceId()
                + "/alert-rules"
                + "/"
                + pair.betaRule();

        expect404(forged, pair.beta().org().ownerBearer());
    }

    // --- 59: serviço de outro tenant no caminho próprio ----------------------

    @Test
    void serviceFromAnotherTenantInsideOwnPathReturns404() throws Exception {
        Pair pair = buildPair();

        // projeto legítimo de Beta + serviço de Alpha → 404 no nível do serviço
        String forged = "/api/v1/organizations/"
                + pair.beta().org().organizationId()
                + "/projects/"
                + pair.beta().projectId()
                + "/services/"
                + pair.alpha().serviceId()
                + "/alert-rules";

        expect404(forged, pair.beta().org().ownerBearer());
    }

    // --- 60: regra de outro serviço no MESMO projeto -------------------------

    @Test
    void getRuleFromOtherService_returns404() throws Exception {
        Scene scene = newScene("alert-idor-siblings", "Org AlertRule Siblings");
        String rule = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra do serviço A");
        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), "Serviço B");

        // a regra existe, mas não pertence ao serviço do path
        expect404(
                alertRuleUrl(scene.org().organizationId(), scene.projectId(), sibling, rule),
                scene.org().ownerBearer());

        // e um PATCH não consegue alcançá-la pelo path errado (nada escrito)
        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), sibling, rule))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Tomado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        mockMvc.perform(get(alertRuleUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId(), rule))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Regra do serviço A"));
    }

    // --- 61: regra de outro tenant alcançada pelo caminho próprio ------------

    @Test
    void ruleFromOtherTenantViaOwnPathReturns404() throws Exception {
        Pair pair = buildPair();

        // id de Alpha plugado na cadeia de Beta → 404 genérico
        expect404Read(pair.beta(), pair.betaRule(), pair.alphaRule());
    }

    // --- 62: UUID válido fora da cadeia --------------------------------------

    @Test
    void validUuidNotInChain_returns404() throws Exception {
        Scene scene = newScene("alert-idor-random", "Org AlertRule Random");

        expect404(
                alertRuleUrl(
                        scene.org().organizationId(),
                        scene.projectId(),
                        scene.serviceId(),
                        UUID.randomUUID().toString()),
                scene.org().ownerBearer());
    }

    @Test
    void malformedRuleIdReturns404Never500() throws Exception {
        Scene scene = newScene("alert-idor-malformed", "Org AlertRule Malformed");

        expect404(
                alertRuleUrl(
                        scene.org().organizationId(),
                        scene.projectId(),
                        scene.serviceId(),
                        "not-a-uuid"),
                scene.org().ownerBearer());
    }

    // --- apoio ---------------------------------------------------------------

    private void expect404Read(Scene holder, String holderRule, String foreignRule) throws Exception {
        expect404(
                alertRuleUrl(
                        holder.org().organizationId(), holder.projectId(), holder.serviceId(), foreignRule),
                holder.org().ownerBearer());
        // a própria regra do holder continua viva (o 404 não apagou nada)
        mockMvc.perform(get(alertRuleUrl(
                                holder.org().organizationId(), holder.projectId(), holder.serviceId(), holderRule))
                        .header(HttpHeaders.AUTHORIZATION, holder.org().ownerBearer()))
                .andExpect(status().isOk());
    }

    private void expect404(String url, String bearer) throws Exception {
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }
}
