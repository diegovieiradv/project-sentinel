package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.model.Membership;
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
 * CRUD de organização (Prompt 04) contra contexto real: Spring Security
 * stateless + CSRF real, JPA, Flyway V4 e PostgreSQL de Testcontainers.
 *
 * <p>Cobertura: criação atômica (org + Membership ADMIN), listagem estritamente
 * por membership, detalhe tenant-scoped com 404 anti-enumeração e o contrato
 * 405 do método inexistente.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationIntegrationTest extends AbstractTenantIntegrationTest {

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

    // --- POST /api/v1/organizations -----------------------------------------

    @Test
    void createOrganizationReturns201AndPersistsAdminMembershipInSameTransaction() throws Exception {
        String bearer = registerAndLogin("Alice Owner", "alice-create@example.com");

        MvcResult result = mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        UUID organizationId = UUID.fromString((String) responseBody(result).get("id"));
        User creator = userRepository.findByEmail("alice-create@example.com").orElseThrow();

        // a organização existe e o criador é o ADMIN — nas DUAS tabelas
        Membership membership = membershipRepository
                .findByUserIdAndOrganizationId(creator.getId(), organizationId)
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(Role.ADMIN);
        assertThat(membership.getOrganization().getName()).isEqualTo("Acme");
    }

    @Test
    void createOrganizationWithBlankNameReturns400() throws Exception {
        String bearer = registerAndLogin("Blank", "blank-name-org@example.com");

        mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createOrganizationWithOversizedNameReturns400() throws Exception {
        String bearer = registerAndLogin("Long", "long-name-org@example.com");

        // teto da coluna VARCHAR(128) da migration V4 — rejeitado antes do banco
        mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "n".repeat(129)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createOrganizationWithMalformedJsonReturns400ErrorShapeWithoutParserInternals()
            throws Exception {
        String bearer = registerAndLogin("Malformed JSON", "malformed-json-org@example.com");

        // JSON truncado autenticado: 400 no shape padrão, nunca 500 — e sem
        // vazar o detalhe do parser Jackson no corpo
        MvcResult result = mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/organizations"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("JSON parse")
                .doesNotContain("end-of-input")
                .doesNotContain("Exception")
                .doesNotContain("stackTrace");
    }

    @Test
    void createOrganizationWithWrongJsonTypeReturns400ErrorShape() throws Exception {
        String bearer = registerAndLogin("Wrong Type", "wrong-type-org@example.com");

        // objeto onde o DTO espera string → desserialização impossível → 400
        mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": {\"nested\": \"object\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.path").value("/api/v1/organizations"));
    }

    @Test
    void createOrganizationWithoutAuthenticationReturns401() throws Exception {
        // sem Authorization (CSRF presente: sem ele o filtro daria 403 antes)
        mockMvc.perform(postCsrf("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ghost Org"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/organizations"));
    }

    // --- GET /api/v1/organizations ------------------------------------------

    @Test
    void listOrganizationsReturnsOnlyMembershipsOfCurrentUser() throws Exception {
        String alice = registerAndLogin("Alice List", "alice-list@example.com");
        String bob = registerAndLogin("Bob List", "bob-list@example.com");

        String aliceFirst = createOrganization(alice, "Alice Corp");
        String aliceSecond = createOrganization(alice, "Alice Labs");
        createOrganization(bob, "Bob Corp");

        MvcResult result = mockMvc.perform(get("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body =
                objectMapper.readValue(result.getResponse().getContentAsString(), List.class);

        assertThat(body).hasSize(2);
        assertThat(body)
                .extracting(entry -> entry.get("id"))
                .containsExactlyInAnyOrder(aliceFirst, aliceSecond);
        assertThat(body)
                .allSatisfy(entry -> assertThat(entry.get("role")).isEqualTo("ADMIN"))
                .noneSatisfy(entry -> assertThat(entry.get("name")).isEqualTo("Bob Corp"));
    }

    @Test
    void listOrganizationsWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- GET /api/v1/organizations/{organizationId} --------------------------

    @Test
    void getOrganizationReturnsDetailWithRoleOfCurrentUser() throws Exception {
        String bearer = registerAndLogin("Alice Detail", "alice-detail@example.com");
        String organizationId = createOrganization(bearer, "Detail Corp");

        mockMvc.perform(get("/api/v1/organizations/" + organizationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizationId))
                .andExpect(jsonPath("$.name").value("Detail Corp"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void getOrganizationReturns404ForNonMemberWithoutRevealingExistence() throws Exception {
        String owner = registerAndLogin("Owner Detail", "owner-detail@example.com");
        String stranger = registerAndLogin("Stranger", "stranger-detail@example.com");
        String organizationId = createOrganization(owner, "Private Corp");

        // a organização EXISTE, mas o usuário não é membro → mesmo 404
        // genérico de uma rota qualquer: nada sobre a organização vaza
        mockMvc.perform(get("/api/v1/organizations/" + organizationId)
                        .header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/organizations/" + organizationId));
    }

    @Test
    void getOrganizationWithMalformedIdReturns404Not500() throws Exception {
        String bearer = registerAndLogin("Malformed", "malformed-org@example.com");

        // path variable fora do formato UUID é tratado pelo interceptor como
        // organização inexistente — nunca como erro interno
        mockMvc.perform(get("/api/v1/organizations/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- contrato 405 (método sem mapping) ----------------------------------

    @Test
    void unsupportedMethodOnExistingRouteReturns405ErrorShape() throws Exception {
        String bearer = registerAndLogin("Method Owner", "method-owner@example.com");
        String organizationId = createOrganization(bearer, "Method Corp");

        mockMvc.perform(deleteCsrf("/api/v1/organizations/" + organizationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"))
                .andExpect(jsonPath("$.path")
                        .value("/api/v1/organizations/" + organizationId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsupportedMethodOnCollectionReturns405ErrorShape() throws Exception {
        String bearer = registerAndLogin("Method Owner 2", "method-owner2@example.com");

        mockMvc.perform(deleteCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"))
                .andExpect(jsonPath("$.path").value("/api/v1/organizations"));
    }

    @Test
    void putMethodOnExistingRouteReturns405ErrorShape() throws Exception {
        String bearer = registerAndLogin("Method Put Owner", "method-put-owner@example.com");
        String organizationId = createOrganization(bearer, "Method Put Corp");

        // PUT existe na rota (path) mas não tem mapping: 405 no shape padrão,
        // nunca o 500 que o catch-all genérico produziria
        mockMvc.perform(putCsrf("/api/v1/organizations/" + organizationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method PUT not allowed"))
                .andExpect(jsonPath("$.path")
                        .value("/api/v1/organizations/" + organizationId))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
