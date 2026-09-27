package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.model.Role;
import com.sentinel.organization.repository.MembershipRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stale authorization (Prompt 05): a autoridade é consultada NO SERVIDOR a
 * cada requisição, com o MESMO access token — o JWT nunca carrega papel nem
 * tenant, então promover/rebaixar/remover um membro tem efeito imediato sem
 * exigir refresh.
 *
 * <p>Cobertura: papel alterado no meio da vida do token (200 → 403 → 200, sem
 * novo login) e membership removida (o mesmo token passa a receber 404
 * genérico naquele tenant e a organização some da listagem).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaleAuthorizationTest extends AbstractTenantIntegrationTest {

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

    private record Fixture(String ownerBearer, String organizationId, String ownerEmail) {
    }

    private Fixture newOrganization(String ownerEmail, String organizationName) throws Exception {
        String bearer = registerAndLogin("Owner", ownerEmail);
        return new Fixture(bearer, createOrganization(bearer, organizationName), ownerEmail);
    }

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

    private void changeRole(
            String bearerToken, String organizationId, String membershipId, String role) throws Exception {
        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/" + organizationId + "/members/" + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", role))))
                .andExpect(status().isOk());
    }

    @Test
    void sameTokenFollowsRoleChangesWithoutRefresh() throws Exception {
        Fixture org = newOrganization("owner-stale-role@example.com", "Stale Role Corp");
        register("Stale Bob", "stale-bob@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "stale-bob@example.com", "OPERATOR");

        // UM token emitido no login de Bob — usado do início ao fim, sem refresh
        String bob = bearer(loginOk("stale-bob@example.com"));
        String membersUrl = "/api/v1/organizations/" + org.organizationId() + "/members";
        String bobMembershipId = membershipIdOf("stale-bob@example.com", org.organizationId());
        String ownerMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        // OPERATOR: membro (detalhe OK) mas sem a listagem admin-only
        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId())
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
        mockMvc.perform(get(membersUrl).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isForbidden());

        // ADMIN promove Bob → o MESMO token ganha acesso imediatamente
        changeRole(org.ownerBearer(), org.organizationId(), bobMembershipId, "ADMIN");
        mockMvc.perform(get(membersUrl).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // ADMIN rebaixa Bob para VIEWER → o MESMO token perde os quatro endpoints
        changeRole(org.ownerBearer(), org.organizationId(), bobMembershipId, "VIEWER");

        mockMvc.perform(get(membersUrl).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        mockMvc.perform(postCsrf(membersUrl)
                        .header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "stale-bob@example.com", "role", "VIEWER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patchCsrf(membersUrl + "/" + ownerMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(deleteCsrf(membersUrl + "/" + ownerMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isForbidden());

        // continua MEMBRO: o detalhe reflete o papel novo lido nesta requisição
        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // e o papel novo é o que está no banco
        assertThat(roleOf("stale-bob@example.com", org.organizationId())).isEqualTo(Role.VIEWER);
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
    }

    @Test
    void removedMembershipMakesTheSameTokenLoseTenantAccess() throws Exception {
        Fixture org = newOrganization("owner-stale-remove@example.com", "Stale Remove Corp");
        register("Stale Carol", "stale-carol@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "stale-carol@example.com", "OPERATOR");

        String carol = bearer(loginOk("stale-carol@example.com"));
        String carolMembershipId = membershipIdOf("stale-carol@example.com", org.organizationId());
        UUID carolUserId = userRepository.findByEmail("stale-carol@example.com").orElseThrow().getId();

        // antes da remoção: membro do tenant
        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId)
                        .header(HttpHeaders.AUTHORIZATION, carol))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, carol))
                .andExpect(status().isForbidden());

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId
                                        + "/members/"
                                        + carolMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNoContent());

        // MESMO token, sem refresh: o tenant responde como qualquer outro
        // inexistente para ela — 404 genérico, nunca 403 que confirme a org
        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId)
                        .header(HttpHeaders.AUTHORIZATION, carol))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, carol))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // a listagem de organizações não a mostra mais
        MvcResult listing = mockMvc.perform(get("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, carol))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body =
                objectMapper.readValue(listing.getResponse().getContentAsString(), List.class);
        assertThat(body)
                .extracting(entry -> entry.get("id"))
                .doesNotContain(org.organizationId());

        // banco: a membership sumiu, a organização permanece com o seu ADMIN
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        carolUserId, UUID.fromString(org.organizationId())))
                .isEmpty();
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
    }
}
