package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.web.TenantScoped;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Resolução de tenant ponta a ponta (Prompt 04, §13/§14).
 *
 * <p>Usa um <b>controller-probe</b> registrado via {@code @TestConfiguration}
 * aninhado (detectado automaticamente pelo {@code @SpringBootTest}) para
 * simular um módulo futuro — ex.: {@code /projects} — ou seja, um handler
 * {@code @TenantScoped} <b>sem</b> {@code organizationId} no path. É o único
 * caso em que o 400 {@code TENANT_REQUIRED} (fonte de tenant ausente) de fato
 * acontece; os endpoints do Prompt 04 sempre trazem a organização no path.</p>
 *
 * <p>Coerência path × header, 404 anti-enumeração, 401 antes do interceptor e
 * limpeza do ThreadLocal entre requisições também são verificados aqui.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiTenantScenarioTest extends AbstractTenantIntegrationTest {

    private static final String PROBE = "/api/v1/probe/projects";

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

    /**
     * Nested {@code @TestConfiguration} é detectado e mesclado com a
     * configuração primária do {@code @SpringBootTest} — por isso o probe não
     * precisa de {@code @Import} explícito.
     */
    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /**
     * Módulo futuro simulado: tenant-scoped só pelo header
     * {@code X-Organization-Id} (sem organizationId no path). Devolve o
     * contexto resolvido para o teste verificar o que o interceptor gravou.
     */
    @RestController
    static class ProbeController {

        @GetMapping(PROBE)
        @TenantScoped
        public Map<String, Object> probe() {
            TenantContext.Context context = TenantContext.require();
            return Map.of(
                    "organizationId", context.organization().getId().toString(),
                    "userId", context.user().getId().toString(),
                    "role", context.role().name());
        }
    }

    // --- resolução via header -----------------------------------------------

    @Test
    void probeWithHeaderResolvesTenantContextForFutureModules() throws Exception {
        String bearer = registerAndLogin("Probe Header", "probe-header@example.com");
        String organizationId = createOrganization(bearer, "Probe Header Corp");

        MvcResult result = mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(ORGANIZATION_HEADER, organizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(organizationId))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        UUID userId = userRepository.findByEmail("probe-header@example.com")
                .orElseThrow()
                .getId();
        assertThat(responseBody(result).get("userId")).isEqualTo(userId.toString());
    }

    @Test
    void probeWithoutOrganizationSourceReturns400TenantRequired() throws Exception {
        String bearer = registerAndLogin("Probe NoSource", "probe-nosource@example.com");

        // sem path organizationId e sem header → o contexto não existe
        mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Organization context required"))
                .andExpect(jsonPath("$.path").value(PROBE));
    }

    @Test
    void probeWithoutAuthenticationReturns401BeforeTenantResolution() throws Exception {
        // a cadeia de segurança roda ANTES do DispatcherServlet: sem Bearer a
        // resposta é 401, nunca o 400 de "sem tenant"
        mockMvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- 404 anti-enumeração -------------------------------------------------

    @Test
    void unknownAndForeignOrganizationsReturnTheSame404Body() throws Exception {
        String owner = registerAndLogin("Probe Owner", "probe-owner@example.com");
        String nonMember = registerAndLogin("Probe NonMember", "probe-nonmember@example.com");
        String ownerOrganizationId = createOrganization(owner, "Probe Owner Corp");
        String unknownOrganizationId = UUID.randomUUID().toString();

        Map<String, Object> unknown = responseBody(mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, nonMember)
                        .header(ORGANIZATION_HEADER, unknownOrganizationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn());

        Map<String, Object> foreign = responseBody(mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, nonMember)
                        .header(ORGANIZATION_HEADER, ownerOrganizationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn());

        // "organização inexistente" e "organização existente sem membership"
        // são INDISTINGUÍVEIS pelo cliente
        assertThat(unknown.get("code")).isEqualTo(foreign.get("code"));
        assertThat(unknown.get("message")).isEqualTo(foreign.get("message"));
        assertThat(unknown.get("message")).isEqualTo("Resource not found");
    }

    @Test
    void malformedOrganizationHeaderReturns404Not500() throws Exception {
        String bearer = registerAndLogin("Probe Malformed", "probe-malformed@example.com");

        mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(ORGANIZATION_HEADER, "not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- coerência path × header ---------------------------------------------

    @Test
    void pathAndHeaderMustAgreeOtherwise404() throws Exception {
        String bearer = registerAndLogin("Probe Path", "probe-path@example.com");
        String organizationA = createOrganization(bearer, "Probe Path A");
        String organizationB = createOrganization(bearer, "Probe Path B");

        // divergente → mesmo 404 genérico (não revela qual dos dois existe)
        mockMvc.perform(get("/api/v1/organizations/" + organizationA)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(ORGANIZATION_HEADER, organizationB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // convergente → resolvido
        mockMvc.perform(get("/api/v1/organizations/" + organizationA)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(ORGANIZATION_HEADER, organizationA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizationA));
    }

    @Test
    void pathAloneIsEnoughWhenHeaderIsAbsent() throws Exception {
        String bearer = registerAndLogin("Probe PathOnly", "probe-pathonly@example.com");
        String organizationId = createOrganization(bearer, "Probe PathOnly Corp");

        mockMvc.perform(get("/api/v1/organizations/" + organizationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizationId));
    }

    // --- limpeza do ThreadLocal -----------------------------------------------

    @Test
    void tenantContextIsClearedAfterSuccessAndAfterFailure() throws Exception {
        String alice = registerAndLogin("Probe Clear A", "probe-clear-a@example.com");
        String bob = registerAndLogin("Probe Clear B", "probe-clear-b@example.com");
        String aliceOrganizationId = createOrganization(alice, "Probe Clear Corp");

        // sucesso → contexto gravado e removido no afterCompletion
        mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .header(ORGANIZATION_HEADER, aliceOrganizationId))
                .andExpect(status().isOk());
        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();

        // falha de resolução → removido antes de propagar o erro
        mockMvc.perform(get(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .header(ORGANIZATION_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertThat(TenantContext.isActive()).as("failure must not leak").isFalse();

        // requisição seguinte (outro usuário, sem header) NÃO herda nada:
        // se o contexto vazasse, responderia 200 com a organização de Alice
        mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        assertThat(TenantContext.isActive()).isFalse();
    }
}
