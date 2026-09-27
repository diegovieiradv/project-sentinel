package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Gestão de membros (Prompt 04) contra contexto real — todos os endpoints
 * {@code /members} exigem ADMIN da própria organização.
 *
 * <p>Cobertura: add/list/role/delete, isolamento entre organizações, 404
 * anti-enumeração, 403 para papéis não-ADMIN (VIEWER e OPERATOR, nos quatro
 * endpoints), 409 do último ADMIN — inclusive sob concorrência —, caminho
 * feliz multi-ADMIN e o contrato de erro shape (inclusive role inválido →
 * 400, nunca 500).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MembershipIntegrationTest extends AbstractTenantIntegrationTest {

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

    // --- POST .../members ---------------------------------------------------

    @Test
    void addMemberReturns201AndPersistsMembership() throws Exception {
        Fixture org = newOrganization("owner-add@example.com", "Add Corp");
        register("Member One", "member-one@example.com");

        MvcResult result = mockMvc.perform(postCsrf(
                                "/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "member-one@example.com", "role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Member One"))
                .andExpect(jsonPath("$.email").value("member-one@example.com"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        assertThat(responseBody(result).get("membershipId"))
                .isEqualTo(membershipIdOf("member-one@example.com", org.organizationId()));
    }

    @Test
    void addMemberWithUnknownEmailReturns404Generic() throws Exception {
        Fixture org = newOrganization("owner-unknown-member@example.com", "Unknown Member Corp");

        // mesma resposta de uma organização inexistente: não revela se a
        // conta existe
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "nobody-registered@example.com",
                                "role", "VIEWER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void addMemberWithInvalidRoleReturns400Not500() throws Exception {
        Fixture org = newOrganization("owner-bad-role@example.com", "Bad Role Corp");
        register("Member Role", "member-role@example.com");

        // papel fora do domínio (ADMIN/OPERATOR/VIEWER) → 400 com mensagem de
        // campo; nunca o 500 que a desserialização de enum produziria
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "member-role@example.com",
                                "role", "SUPERUSER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "role: must be one of ADMIN, OPERATOR, VIEWER"));
    }

    @Test
    void addMemberWithoutRoleReturns400ExplicitRoleIsRequired() throws Exception {
        Fixture org = newOrganization("owner-no-role@example.com", "No Role Corp");
        register("Member NoRole", "member-norole@example.com");

        // sem default implícito: o papel deve ser nomeado por quem concede
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "member-norole@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("role: must not be blank"));
    }

    @Test
    void addMemberWithLowercaseInvalidRoleStillReturns400() throws Exception {
        Fixture org = newOrganization("owner-lowercase-bad-role@example.com", "Lowercase Bad Role Corp");
        register("Member LowerRole", "member-lowerrole@example.com");

        // parseRole normaliza com toUpperCase(Locale.ROOT) também no caminho de
        // FALHA: "superuser" não vira ADMIN nem vira 500 de enum — 400 com a
        // mesma mensagem de domínio
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "member-lowerrole@example.com",
                                "role", "superuser"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "role: must be one of ADMIN, OPERATOR, VIEWER"));
    }

    @Test
    void addMemberRoleIsCaseInsensitiveAndPersistsUppercase() throws Exception {
        Fixture org = newOrganization("owner-role-case@example.com", "Role Case Corp");
        register("Role Lower", "role-lower@example.com");
        register("Role Mixed", "role-mixed@example.com");

        // caixa baixa → normalizado para o enum
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "role-lower@example.com",
                                "role", "viewer"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // caixa mista → normalizado para o enum
        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "role-mixed@example.com",
                                "role", "Admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(roleOf("role-lower@example.com", org.organizationId())).isEqualTo(Role.VIEWER);
        assertThat(roleOf("role-mixed@example.com", org.organizationId())).isEqualTo(Role.ADMIN);
    }

    @Test
    void addSameMemberTwiceReturns409() throws Exception {
        Fixture org = newOrganization("owner-dup-member@example.com", "Dup Member Corp");
        register("Member Dup", "member-dup@example.com");

        String payload = json(Map.of("email", "member-dup@example.com", "role", "VIEWER"));

        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(postCsrf("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_ALREADY_EXISTS"));
    }

    // --- GET .../members ----------------------------------------------------

    @Test
    void listMembersAsAdminReturnsEveryoneWithRolesAndNoSecrets() throws Exception {
        Fixture org = newOrganization("owner-list@example.com", "List Corp");
        register("List One", "list-one@example.com");
        register("List Two", "list-two@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "list-one@example.com", "VIEWER");
        addMember(org.ownerBearer(), org.organizationId(), "list-two@example.com", "OPERATOR");

        MvcResult result = mockMvc.perform(get(
                                "/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                objectMapper.readValue(result.getResponse().getContentAsString(), List.class);

        // criador + os dois convidados — e o e-mail é o ÚNICO dado de outro
        // usuário exposto na API, por isso a rota é admin-only
        assertThat(members).hasSize(3);
        assertThat(members)
                .extracting(member -> member.get("email"))
                .containsExactlyInAnyOrder(
                        "owner-list@example.com",
                        "list-one@example.com",
                        "list-two@example.com");
        assertThat(members)
                .filteredOn(member -> member.get("email").equals("list-one@example.com"))
                .allSatisfy(member -> assertThat(member.get("role")).isEqualTo("VIEWER"));
        assertThat(members)
                .allSatisfy(member -> assertThat(member)
                        .doesNotContainKey("password")
                        .doesNotContainKey("passwordHash"));
    }

    @Test
    void listMembersAsViewerReturns403AccessDenied() throws Exception {
        Fixture org = newOrganization("owner-viewer-403@example.com", "Viewer 403 Corp");
        register("Viewer User", "viewer-list@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "viewer-list@example.com", "VIEWER");

        String viewerBearer = bearer(loginOk("viewer-list@example.com"));

        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void listMembersWithoutAuthenticationReturns401() throws Exception {
        Fixture org = newOrganization("owner-noauth-members@example.com", "NoAuth Members Corp");

        mockMvc.perform(get("/api/v1/organizations/" + org.organizationId() + "/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void listMembersOfOrganizationUserIsNotMemberOfReturns404() throws Exception {
        Fixture foreign = newOrganization("owner-foreign@example.com", "Foreign Corp");
        // o estranho É membro de OUTRA organização — o 404 prova que a checagem
        // é membership POR organização, não "possui alguma membership"
        String stranger = registerAndLogin("Stranger Member", "stranger-members@example.com");
        createOrganization(stranger, "Stranger Own Corp");

        mockMvc.perform(get("/api/v1/organizations/" + foreign.organizationId() + "/members")
                        .header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void listMembersWithMalformedOrganizationIdReturns404Not500() throws Exception {
        Fixture org = newOrganization("owner-malformed-org-members@example.com", "Malformed Org Corp");

        // organizationId fora do formato UUID é tratado pelo interceptor como
        // organização inexistente — o mesmo 404 da rota de detalhe, nunca 500
        mockMvc.perform(get("/api/v1/organizations/not-a-uuid/members")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- PATCH .../members/{membershipId} ------------------------------------

    @Test
    void updateRoleAsAdminChangesRoleAndPersists() throws Exception {
        Fixture org = newOrganization("owner-promote@example.com", "Promote Corp");
        register("Promotable", "promotable@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "promotable@example.com", "VIEWER");
        String membershipId = membershipIdOf("promotable@example.com", org.organizationId());

        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membershipId))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.email").value("promotable@example.com"));

        UUID userId = userRepository.findByEmail("promotable@example.com").orElseThrow().getId();
        assertThat(membershipRepository
                        .findByUserIdAndOrganizationId(userId, UUID.fromString(org.organizationId()))
                        .orElseThrow()
                        .getRole())
                .isEqualTo(Role.OPERATOR);
    }

    @Test
    void updateRoleWithInvalidValueReturns400() throws Exception {
        Fixture org = newOrganization("owner-bad-promote@example.com", "Bad Promote Corp");
        register("Fixable", "fixable@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "fixable@example.com", "VIEWER");
        String membershipId = membershipIdOf("fixable@example.com", org.organizationId());

        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "ROOT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "role: must be one of ADMIN, OPERATOR, VIEWER"));
    }

    @Test
    void updateRoleWithEmptyStringReturns400NotBlank() throws Exception {
        Fixture org = newOrganization("owner-empty-role@example.com", "Empty Role Corp");
        register("Empty Role", "empty-role@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "empty-role@example.com", "VIEWER");
        String membershipId = membershipIdOf("empty-role@example.com", org.organizationId());

        // string vazia != campo ausente: ambos caem no @NotBlank com a mesma
        // mensagem de campo (nunca o 500 de enum/parse)
        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("role: must not be blank"));

        assertThat(roleOf("empty-role@example.com", org.organizationId())).isEqualTo(Role.VIEWER);
    }

    @Test
    void updateRoleIsCaseInsensitiveAndPersistsUppercase() throws Exception {
        Fixture org = newOrganization("owner-patch-case@example.com", "Patch Case Corp");
        register("Patch Case", "patch-case@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "patch-case@example.com", "VIEWER");
        String membershipId = membershipIdOf("patch-case@example.com", org.organizationId());

        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "Operator"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        assertThat(roleOf("patch-case@example.com", org.organizationId())).isEqualTo(Role.OPERATOR);
    }

    @Test
    void updateMembershipBelongingToAnotherOrganizationReturns404() throws Exception {
        // o MESMO usuário administra as duas organizações: o 404 vem do escopo
        // do tenant (membership não está no FOR UPDATE da organização alvo),
        // não da falta de permissão do chamador
        Fixture orgA = newOrganization("owner-two-orgs@example.com", "Org A Corp");
        String bearer = orgA.ownerBearer();
        String orgB = createOrganization(bearer, "Org B Corp");
        register("Cross Member", "cross-member@example.com");
        addMember(bearer, orgB, "cross-member@example.com", "VIEWER");
        String membershipInB = membershipIdOf("cross-member@example.com", orgB);

        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + orgA.organizationId()
                                        + "/members/"
                                        + membershipInB)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void demoteLastAdminReturns409() throws Exception {
        Fixture org = newOrganization("owner-last-admin-demote@example.com", "Last Admin Corp");
        String ownMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + ownMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value(
                        "Organization must keep at least one admin"));
    }

    // --- DELETE .../members/{membershipId} -----------------------------------

    @Test
    void removeMemberAsAdminReturns204AndDeletesRow() throws Exception {
        Fixture org = newOrganization("owner-remove@example.com", "Remove Corp");
        register("Removable", "removable@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "removable@example.com", "OPERATOR");
        String membershipId = membershipIdOf("removable@example.com", org.organizationId());

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + membershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEmpty());

        UUID userId = userRepository.findByEmail("removable@example.com").orElseThrow().getId();
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        userId, UUID.fromString(org.organizationId())))
                .isEmpty();
    }

    @Test
    void removeLastAdminReturns409() throws Exception {
        Fixture org = newOrganization("owner-last-admin-remove@example.com", "Last Admin Remove Corp");
        String ownMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + ownMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value(
                        "Organization must keep at least one admin"));
    }

    @Test
    void removeMembershipWithMalformedIdReturns404Not500() throws Exception {
        Fixture org = newOrganization("owner-malformed-member@example.com", "Malformed Member Corp");

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void updateMembershipWithMalformedIdReturns404Not500() throws Exception {
        Fixture org = newOrganization("owner-malformed-patch@example.com", "Malformed Patch Corp");

        // parse manual no controller: id fora do formato UUID → o MESMO 404
        // genérico da membership inexistente (nunca MethodArgumentTypeMismatch
        // nem 500)
        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void removeMemberAsViewerReturns403() throws Exception {
        Fixture org = newOrganization("owner-viewer-remove@example.com", "Viewer Remove Corp");
        register("Viewer Remover", "viewer-remove@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "viewer-remove@example.com", "VIEWER");
        String viewerBearer = bearer(loginOk("viewer-remove@example.com"));
        String ownMembershipId = membershipIdOf("viewer-remove@example.com", org.organizationId());

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + ownMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // --- autorização por papel: OPERATOR e VIEWER nos quatro endpoints ------

    @Test
    void operatorGets403OnEveryMemberEndpoint() throws Exception {
        Fixture org = newOrganization("owner-operator-403@example.com", "Operator 403 Corp");
        register("Operator User", "operator-members@example.com");
        register("Operator Add Target", "operator-add-target@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "operator-members@example.com", "OPERATOR");
        String operatorBearer = bearer(loginOk("operator-members@example.com"));
        String membersUrl = "/api/v1/organizations/" + org.organizationId() + "/members";
        String ownerMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        // GET: a listagem é admin-only porque é o único payload que expõe
        // e-mails de outros membros
        mockMvc.perform(get(membersUrl)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // POST: conceder papel é decisão de ADMIN
        mockMvc.perform(postCsrf(membersUrl)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "operator-add-target@example.com",
                                "role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // PATCH: rebaixar o dono da organização também é 403, não 404/500
        mockMvc.perform(patchCsrf(membersUrl + "/" + ownerMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // DELETE
        mockMvc.perform(deleteCsrf(membersUrl + "/" + ownerMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // as quatro operações pararam na checagem de papel: nada mudou
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
        UUID addTargetUserId = userRepository
                .findByEmail("operator-add-target@example.com")
                .orElseThrow()
                .getId();
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        addTargetUserId, UUID.fromString(org.organizationId())))
                .isEmpty();
    }

    @Test
    void viewerGets403OnWriteMemberEndpoints() throws Exception {
        Fixture org = newOrganization("owner-viewer-write@example.com", "Viewer Write Corp");
        register("Viewer Writer", "viewer-write@example.com");
        register("Viewer Add Target", "viewer-add-target@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "viewer-write@example.com", "VIEWER");
        String viewerBearer = bearer(loginOk("viewer-write@example.com"));
        String membersUrl = "/api/v1/organizations/" + org.organizationId() + "/members";
        String ownerMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        mockMvc.perform(postCsrf(membersUrl)
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "viewer-add-target@example.com",
                                "role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        mockMvc.perform(patchCsrf(membersUrl + "/" + ownerMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // GET/DELETE do VIEWER já são cobertos pelos testes acima; aqui o
        // regressão é a ESCRITA não passar da autorização
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
        UUID addTargetUserId = userRepository
                .findByEmail("viewer-add-target@example.com")
                .orElseThrow()
                .getId();
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        addTargetUserId, UUID.fromString(org.organizationId())))
                .isEmpty();
    }

    // --- caminho feliz multi-ADMIN (ressalva da revisão) ---------------------

    @Test
    void demoteSecondAdminReturns200BecauseAnotherAdminRemains() throws Exception {
        Fixture org = newOrganization("owner-two-admins@example.com", "Two Admins Corp");
        register("Second Admin", "second-admin@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "second-admin@example.com", "ADMIN");
        String secondAdminMembershipId = membershipIdOf("second-admin@example.com", org.organizationId());

        // com DOUS administradores o rebaixamento é legítimo: o tenant não
        // fica órfão e a regra do último ADMIN não deve bloquear
        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + secondAdminMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(secondAdminMembershipId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        assertThat(roleOf("second-admin@example.com", org.organizationId()))
                .isEqualTo(Role.VIEWER);
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
    }

    @Test
    void reSetSoleAdminToAdminReturns200AndKeepsAdmin() throws Exception {
        Fixture org = newOrganization("owner-restate-admin@example.com", "Restate Admin Corp");
        String ownMembershipId = membershipIdOf(org.ownerEmail(), org.organizationId());

        // ADMIN → ADMIN é um no-op idempotente: não rebaixa ninguém, então o
        // último ADMIN pode reafirmar o próprio papel sem 409
        mockMvc.perform(patchCsrf(
                                "/api/v1/organizations/"
                                        + org.organizationId()
                                        + "/members/"
                                        + ownMembershipId)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(ownMembershipId))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
    }

    // --- DELETE cross-tenant (ressalva da revisão) ---------------------------

    @Test
    void removeMembershipBelongingToAnotherOrganizationReturns404() throws Exception {
        // o MESMO usuário administra as duas organizações: a membership de B
        // não está no FOR UPDATE da organização A → mesmo 404 genérico
        Fixture orgA = newOrganization("owner-two-orgs-delete@example.com", "Org A Delete Corp");
        String bearer = orgA.ownerBearer();
        String orgB = createOrganization(bearer, "Org B Delete Corp");
        register("Cross Delete", "cross-delete@example.com");
        addMember(bearer, orgB, "cross-delete@example.com", "VIEWER");
        String membershipInB = membershipIdOf("cross-delete@example.com", orgB);

        mockMvc.perform(deleteCsrf(
                                "/api/v1/organizations/"
                                        + orgA.organizationId()
                                        + "/members/"
                                        + membershipInB)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // a membership de B permanece intacta
        assertThat(membershipRepository.findById(UUID.fromString(membershipInB))).isPresent();
    }

    // --- concorrência: o lock do tenant serializa as operações ---------------

    @Test
    void concurrentDeleteOfSameMembershipIsSerializedToOne204AndOne404() throws Exception {
        Fixture org = newOrganization("owner-concurrent-delete@example.com", "Concurrent Delete Corp");
        register("Concurrent Target", "concurrent-target@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "concurrent-target@example.com", "VIEWER");
        UUID targetUserId = userRepository
                .findByEmail("concurrent-target@example.com")
                .orElseThrow()
                .getId();
        String url = "/api/v1/organizations/"
                + org.organizationId()
                + "/members/"
                + membershipIdOf("concurrent-target@example.com", org.organizationId());

        // duas threads apontando para a MESMA membership: o SELECT ... FOR
        // UPDATE serializa — o vencedor apaga (204) e o perdedor relê o
        // conjunto sem a linha (404 genérico). Duas respostas 204 seriam a
        // prova de que a segunda decidiu sobre um snapshot obsoleto.
        List<Integer> statuses = concurrentDeletes(org.ownerBearer(), url);

        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        targetUserId, UUID.fromString(org.organizationId())))
                .isEmpty();
    }

    @Test
    void concurrentDeleteOfSoleAdminNeverLeavesOrganizationWithoutAdmin() throws Exception {
        Fixture org = newOrganization("owner-concurrent-admin@example.com", "Concurrent Admin Corp");
        UUID ownerUserId = userRepository.findByEmail(org.ownerEmail()).orElseThrow().getId();
        String url = "/api/v1/organizations/"
                + org.organizationId()
                + "/members/"
                + membershipIdOf(org.ownerEmail(), org.organizationId());

        // as duas threads tentam remover o ÚNICO ADMIN: ambas decidem dentro
        // do lock, então os dois resultados são 409 — nunca um 204 que deixaria
        // a organização sem administrador
        List<Integer> statuses = concurrentDeletes(org.ownerBearer(), url);

        assertThat(statuses).containsOnly(409);
        Membership ownerMembership = membershipRepository
                .findByUserIdAndOrganizationId(ownerUserId, UUID.fromString(org.organizationId()))
                .orElseThrow();
        assertThat(ownerMembership.getRole()).isEqualTo(Role.ADMIN);
    }

    // --- apoio ---------------------------------------------------------------

    /** Papel corrente de um membro (asserções de estado final). */
    private Role roleOf(String email, String organizationId) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return membershipRepository
                .findByUserIdAndOrganizationId(userId, UUID.fromString(organizationId))
                .orElseThrow()
                .getRole();
    }

    /**
     * Duas requisições DELETE simultâneas (CountDownLatch alinhando o início)
     * devolvendo os dois status HTTP. O CSRF é cacheado na base ANTES das
     * threads, e o latch estabelece o happens-before da leitura.
     */
    private List<Integer> concurrentDeletes(String bearerToken, String url) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>(2);
            for (int attempt = 0; attempt < 2; attempt++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(deleteCsrf(url)
                                    .header(HttpHeaders.AUTHORIZATION, bearerToken))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        }
        finally {
            pool.shutdownNow();
        }
    }
}
