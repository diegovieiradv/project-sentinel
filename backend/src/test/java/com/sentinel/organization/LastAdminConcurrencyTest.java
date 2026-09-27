package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regra do último ADMIN sob concorrência real (Prompt 05).
 *
 * <p>O {@code SELECT ... FOR UPDATE} de {@code MembershipService} serializa
 * as operações do tenant; a contagem de ADMINs é lida DENTRO do lock. Aqui
 * estão os cenários de corrida que o {@link MembershipIntegrationTest} não
 * cobre: rebaixar (PATCH) o último ADMIN, rebaixar × remover na mesma
 * membership e — o caso que só o lock resolve — dois administradores sendo
 * rebaixados/removidos CONCORRENTEMENTE (sem lock, os dois leriam contagem=2
 * e deixariam a organização sem nenhum ADMIN).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LastAdminConcurrencyTest extends AbstractTenantIntegrationTest {

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

    /** Papel do membro, ou {@code null} quando a membership foi removida. */
    private Role roleOfOrNull(String email, String organizationId) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return membershipRepository
                .findByUserIdAndOrganizationId(userId, UUID.fromString(organizationId))
                .map(Membership::getRole)
                .orElse(null);
    }

    private long adminCount(String organizationId) {
        return membershipRepository.findAllByOrganizationId(UUID.fromString(organizationId)).stream()
                .filter(membership -> membership.getRole() == Role.ADMIN)
                .count();
    }

    private String memberUrl(String organizationId, String membershipId) {
        return "/api/v1/organizations/" + organizationId + "/members/" + membershipId;
    }

    // --- último ADMIN: PATCH × PATCH e PATCH × DELETE ------------------------

    @Test
    void concurrentDemoteOfSoleAdminReturns409OnBothSides() throws Exception {
        Fixture org = newOrganization("owner-concurrent-demote@example.com", "Concurrent Demote Corp");
        String url = memberUrl(org.organizationId(), membershipIdOf(org.ownerEmail(), org.organizationId()));

        // duas threads rebaixando o ÚNICO ADMIN: ambas decidem dentro do lock
        // e recebem 409 — nunca um 200 que deixaria o tenant órfão
        List<Integer> statuses = runConcurrently(
                () -> patchRole(url, org.ownerBearer(), "VIEWER"),
                () -> patchRole(url, org.ownerBearer(), "VIEWER"));

        assertThat(statuses).containsOnly(409);
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
        assertThat(adminCount(org.organizationId())).isEqualTo(1);
    }

    @Test
    void concurrentDemoteAndRemoveOfSoleAdminReturns409OnBothSides() throws Exception {
        Fixture org = newOrganization("owner-concurrent-demote-remove@example.com", "Concurrent DR Corp");
        String url = memberUrl(org.organizationId(), membershipIdOf(org.ownerEmail(), org.organizationId()));

        // PATCH × DELETE na mesma membership: o DELETE não pode aproveitar um
        // rebaixamento que o PATCH nem conseguiu aplicar (ambos 409)
        List<Integer> statuses = runConcurrently(
                () -> patchRole(url, org.ownerBearer(), "VIEWER"),
                () -> removeMember(url, org.ownerBearer()));

        assertThat(statuses).containsOnly(409);
        assertThat(roleOf(org.ownerEmail(), org.organizationId())).isEqualTo(Role.ADMIN);
        assertThat(adminCount(org.organizationId())).isEqualTo(1);
    }

    // --- dois ADMINs: a corrida que só o lock resolve ------------------------

    @Test
    void concurrentCrossDemoteOfTwoAdminsLeavesExactlyOneAdmin() throws Exception {
        Fixture org = newOrganization("owner-cross-demote@example.com", "Cross Demote Corp");
        register("Cross Demote Second", "cross-demote-second@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "cross-demote-second@example.com", "ADMIN");

        String ownerUrl = memberUrl(org.organizationId(), membershipIdOf(org.ownerEmail(), org.organizationId()));
        String secondUrl = memberUrl(
                org.organizationId(), membershipIdOf("cross-demote-second@example.com", org.organizationId()));

        // cada thread rebaixa UM admin DIFERENTE. Sem o lock as duas leriam
        // contagem=2 e venceriam — restando ZERO administradores. Com o lock a
        // segunda releitura a contagem já atualizada e é rejeitada.
        List<Integer> statuses = runConcurrently(
                () -> patchRole(ownerUrl, org.ownerBearer(), "VIEWER"),
                () -> patchRole(secondUrl, org.ownerBearer(), "VIEWER"));

        // exatamente UMA das duas vence; a outra é 409 (contagem já em 1) ou
        // 403 (o chamador perdeu o papel antes do serviço — o interceptor
        // resolve o papel na própria requisição)
        assertThat(successCount(statuses)).isEqualTo(1);
        assertThat(statuses).allMatch(status -> status == 200 || status == 409 || status == 403);

        assertThat(adminCount(org.organizationId())).isEqualTo(1);
        assertThat(roleOf(org.ownerEmail(), org.organizationId()).equals(Role.ADMIN)
                        || roleOf("cross-demote-second@example.com", org.organizationId())
                                .equals(Role.ADMIN))
                .isTrue();
    }

    @Test
    void concurrentDemoteAndRemoveAcrossTwoAdminsLeavesExactlyOneAdmin() throws Exception {
        Fixture org = newOrganization("owner-cross-demote-remove@example.com", "Cross DR Corp");
        register("Cross DR Second", "cross-dr-second@example.com");
        addMember(org.ownerBearer(), org.organizationId(), "cross-dr-second@example.com", "ADMIN");

        String ownerUrl = memberUrl(org.organizationId(), membershipIdOf(org.ownerEmail(), org.organizationId()));
        String secondUrl =
                memberUrl(org.organizationId(), membershipIdOf("cross-dr-second@example.com", org.organizationId()));

        // uma thread REBAIXA o segundo ADMIN, a outra REMOVE o dono — sem o
        // lock as duas partiriam de contagem=2 e zerariam os administradores
        List<Integer> statuses = runConcurrently(
                () -> patchRole(secondUrl, org.ownerBearer(), "VIEWER"),
                () -> removeMember(ownerUrl, org.ownerBearer()));

        assertThat(successCount(statuses)).isEqualTo(1);
        assertThat(statuses).allMatch(status -> status == 200 || status == 204 || status == 409 || status == 404);

        // exatamente UM ADMIN restante — e é um dos dois membros do cenário
        assertThat(adminCount(org.organizationId())).isEqualTo(1);
        assertThat(roleOfOrNull(org.ownerEmail(), org.organizationId()) == Role.ADMIN
                        || roleOfOrNull("cross-dr-second@example.com", org.organizationId()) == Role.ADMIN)
                .isTrue();

        // a única escrita vencedora é refletida no banco: se o DELETE venceu,
        // a membership do dono não existe mais; se o PATCH venceu, ela existe
        // com o papel original (a remoção foi rejeitada)
        UUID ownerUserId = userRepository.findByEmail(org.ownerEmail()).orElseThrow().getId();
        assertThat(membershipRepository.findByUserIdAndOrganizationId(
                        ownerUserId, UUID.fromString(org.organizationId()))
                .isPresent())
                .isEqualTo(!statuses.contains(204));
    }

    // --- apoio ---------------------------------------------------------------

    private static long successCount(List<Integer> statuses) {
        return statuses.stream().filter(status -> status == 200 || status == 204).count();
    }

    private int patchRole(String url, String bearerToken, String role) throws Exception {
        return mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", role))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int removeMember(String url, String bearerToken) throws Exception {
        return mockMvc.perform(deleteCsrf(url).header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * Duas ações simultâneas (CountDownLatch alinhando o início) devolvendo os
     * dois status HTTP. O CSRF é aquecido na thread do teste ANTES das
     * threads; o latch estabelece o happens-before da leitura.
     */
    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        csrf();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Integer> right = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            statuses.add(left.get(30, TimeUnit.SECONDS));
            statuses.add(right.get(30, TimeUnit.SECONDS));
            return statuses;
        }
        finally {
            pool.shutdownNow();
        }
    }
}
