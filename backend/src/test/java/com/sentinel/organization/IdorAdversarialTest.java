package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.model.Role;
import com.sentinel.organization.repository.MembershipRepository;
import com.sentinel.organization.repository.OrganizationRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ataques de IDOR / troca de tenant (Prompt 05) contra a fronteira real
 * {@code TenantInterceptor} + regras de domínio.
 *
 * <p>Cenário canônico: Alpha (Alice=ADMIN, Bob=OPERATOR) e Beta (Bob=ADMIN,
 * Alice=VIEWER), mais Charlie sem membership nenhuma. A política de
 * anti-enumeração é fixa: para quem não é membro a resposta é SEMPRE o 404
 * genérico {@code "Resource not found"} — nunca 403 (que confirmaria a
 * existência) — e nenhuma escrita é aplicada.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdorAdversarialTest extends AbstractTenantIntegrationTest {

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    /** Sufixo por teste: cadastro e organizações nunca colidem entre si. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private String alphaId;
    private String betaId;
    private String aliceBearer;
    private String bobBearer;
    private String charlieBearer;
    private String aliceEmail;
    private String bobEmail;
    private String charlieEmail;
    private String aliceAlphaMembershipId;
    private String bobAlphaMembershipId;
    private String bobBetaMembershipId;
    private String aliceBetaMembershipId;

    @BeforeEach
    void buildScenario() throws Exception {
        String suffix = "-idor-" + SEQUENCE.incrementAndGet();
        aliceEmail = "alice" + suffix + "@example.com";
        bobEmail = "bob" + suffix + "@example.com";
        charlieEmail = "charlie" + suffix + "@example.com";

        aliceBearer = registerAndLogin("Alice Alpha", aliceEmail);
        bobBearer = registerAndLogin("Bob Beta", bobEmail);
        charlieBearer = registerAndLogin("Charlie NoTenant", charlieEmail);

        alphaId = createOrganization(aliceBearer, "Alpha" + suffix);
        addMember(aliceBearer, alphaId, bobEmail, "OPERATOR");

        betaId = createOrganization(bobBearer, "Beta" + suffix);
        addMember(bobBearer, betaId, aliceEmail, "VIEWER");

        aliceAlphaMembershipId = membershipIdOf(aliceEmail, alphaId);
        bobAlphaMembershipId = membershipIdOf(bobEmail, alphaId);
        bobBetaMembershipId = membershipIdOf(bobEmail, betaId);
        aliceBetaMembershipId = membershipIdOf(aliceEmail, betaId);
    }

    // --- helpers ------------------------------------------------------------

    private String membershipIdOf(String email, String organizationId) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return membershipRepository
                .findByUserIdAndOrganizationId(userId, UUID.fromString(organizationId))
                .orElseThrow()
                .getId()
                .toString();
    }

    private Role roleOf(String email, String organizationId) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return membershipRepository
                .findByUserIdAndOrganizationId(userId, UUID.fromString(organizationId))
                .orElseThrow()
                .getRole();
    }

    private String membersUrl(String organizationId) {
        return "/api/v1/organizations/" + organizationId + "/members";
    }

    /** O 404 anti-enumeração, exatamente como o resto da API o devolve. */
    private void expectGeneric404(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        actions.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    /** Invariante final de todo teste: o cenário Alpha/Beta não foi alterado. */
    private void assertScenarioIntact() {
        assertThat(roleOf(aliceEmail, alphaId)).isEqualTo(Role.ADMIN);
        assertThat(roleOf(bobEmail, alphaId)).isEqualTo(Role.OPERATOR);
        assertThat(roleOf(bobEmail, betaId)).isEqualTo(Role.ADMIN);
        assertThat(roleOf(aliceEmail, betaId)).isEqualTo(Role.VIEWER);
        assertThat(organizationRepository.findById(UUID.fromString(alphaId)).orElseThrow().getName())
                .startsWith("Alpha-idor-");
        assertThat(organizationRepository.findById(UUID.fromString(betaId)).orElseThrow().getName())
                .startsWith("Beta-idor-");
    }

    // --- leituras cruzadas --------------------------------------------------

    @Test
    void organizationReadsAreScopedToTheCallersMembership() throws Exception {
        // Charlie não é membro de NENHUMA: os dois ids respondem o MESMO 404
        // genérico — a resposta não distingue "existe" de "não existe"
        expectGeneric404(mockMvc.perform(get("/api/v1/organizations/" + alphaId)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));
        expectGeneric404(mockMvc.perform(get("/api/v1/organizations/" + betaId)
                .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

        // quem É membro vê a organização com o SEU papel — nunca o do outro
        mockMvc.perform(get("/api/v1/organizations/" + betaId)
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(betaId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mockMvc.perform(get("/api/v1/organizations/" + alphaId)
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alphaId))
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        assertScenarioIntact();
    }

    @Test
    void everyMemberOperationByANonMemberReturns404AndChangesNothing() throws Exception {
        // membership válida de cada organização no path — o 404 vem da falta
        // de membership de CHARLIE, não de um id inventado
        List<String> organizationIds = List.of(alphaId, betaId);
        List<String> targetMembershipIds = List.of(aliceAlphaMembershipId, bobBetaMembershipId);

        for (int i = 0; i < organizationIds.size(); i++) {
            String organizationId = organizationIds.get(i);
            String targetMembershipId = targetMembershipIds.get(i);
            String url = membersUrl(organizationId);

            // listagem
            expectGeneric404(mockMvc.perform(get(url)
                    .header(HttpHeaders.AUTHORIZATION, charlieBearer)));

            // criação (inclusive tentando se autopromover a ADMIN)
            expectGeneric404(mockMvc.perform(postCsrf(url)
                    .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("email", charlieEmail, "role", "ADMIN")))));

            // alteração de papel
            expectGeneric404(mockMvc.perform(patchCsrf(url + "/" + targetMembershipId)
                    .header(HttpHeaders.AUTHORIZATION, charlieBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("role", "VIEWER")))));

            // remoção
            expectGeneric404(mockMvc.perform(deleteCsrf(url + "/" + targetMembershipId)
                    .header(HttpHeaders.AUTHORIZATION, charlieBearer)));
        }

        // nenhuma escrita aplicada e Charlie continua sem tenant nenhum
        assertScenarioIntact();
        assertThat(membershipRepository.findAllByUserIdWithOrganization(
                        userRepository.findByEmail(charlieEmail).orElseThrow().getId()))
                .isEmpty();
    }

    @Test
    void authorityIsResolvedPerTenantNotPerUser() throws Exception {
        // Bob é ADMIN da Beta, mas apenas OPERATOR da Alpha: a autoridade vem
        // da membership do tenant da requisição, não do usuário
        mockMvc.perform(get(membersUrl(betaId)).header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(membersUrl(alphaId)).header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // Alice é ADMIN da Alpha, mas apenas VIEWER da Beta — efeito espelhado
        mockMvc.perform(get(membersUrl(alphaId)).header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(membersUrl(betaId)).header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // os 403 acima não alteraram nada (nenhuma escrita foi sequer tentada)
        assertScenarioIntact();
    }

    // --- escritas cruzadas --------------------------------------------------

    @Test
    void membershipIdFromAnotherTenantReturns404AndPersists() throws Exception {
        // Alice é ADMIN da Alpha e usa no path da Alpha um membershipId que
        // pertence à BETA: o id não resolve naquele escopo → 404, não 403
        expectGeneric404(mockMvc.perform(patchCsrf(membersUrl(alphaId) + "/" + bobBetaMembershipId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("role", "VIEWER")))));

        expectGeneric404(mockMvc.perform(deleteCsrf(membersUrl(alphaId) + "/" + bobBetaMembershipId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        // a membership da Beta permanece com o papel original
        assertThat(roleOf(bobEmail, betaId)).isEqualTo(Role.ADMIN);
        assertScenarioIntact();
    }

    @Test
    void unknownMembershipIdReturns404WithoutSideEffects() throws Exception {
        String unknown = UUID.randomUUID().toString();

        expectGeneric404(mockMvc.perform(patchCsrf(membersUrl(alphaId) + "/" + unknown)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("role", "VIEWER")))));

        expectGeneric404(mockMvc.perform(deleteCsrf(membersUrl(alphaId) + "/" + unknown)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        assertScenarioIntact();
    }

    @Test
    void pathAndHeaderMismatchOnMemberRoutesReturns404() throws Exception {
        // Alice é ADMIN da Alpha: o path diz Alpha, o header de tenant diz Beta
        // → divergência tratada como recurso inexistente (nunca operação
        // cross-tenant e nunca 403 que confirme qual organização existe)
        expectGeneric404(mockMvc.perform(get(membersUrl(alphaId))
                .header(ORGANIZATION_HEADER, betaId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        expectGeneric404(mockMvc.perform(deleteCsrf(membersUrl(alphaId) + "/" + aliceAlphaMembershipId)
                .header(ORGANIZATION_HEADER, betaId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)));

        // a própria membership da Alpha não foi removida
        assertThat(roleOf(aliceEmail, alphaId)).isEqualTo(Role.ADMIN);
        assertScenarioIntact();
    }
}
