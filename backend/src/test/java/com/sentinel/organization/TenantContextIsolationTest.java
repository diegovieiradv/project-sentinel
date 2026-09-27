package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.organization.context.TenantContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Isolamento do {@link TenantContext} (Prompt 05): o contexto de tenant é um
 * ThreadLocal gravado pelo {@code TenantInterceptor} em cada requisição e
 * removido em {@code afterCompletion} — sucesso ou falha.
 *
 * <p>O {@link MultiTenantScenarioTest} cobre a limpeza sequencial com um
 * controller-probe; aqui o foco é <b>concorrência real</b> com os endpoints
 * verdadeiros de organização/membros: requisições simultâneas de tenants e
 * papéis diferentes nas MESMAS threads nunca enxergam o contexto uma da
 * outra, e nenhuma thread reaproveitada herda o tenant da requisição
 * anterior.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantContextIsolationTest extends AbstractTenantIntegrationTest {

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
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final int ITERATIONS = 5;

    private String aliceBearer;
    private String bobBearer;
    private String aliceOrg;
    private String bobOrg;
    private String sharedOrg;

    @BeforeEach
    void buildScenario() throws Exception {
        String suffix = "-ctx-" + SEQUENCE.incrementAndGet();
        String aliceEmail = "alice" + suffix + "@example.com";
        String bobEmail = "bob" + suffix + "@example.com";

        aliceBearer = registerAndLogin("Ctx Alice", aliceEmail);
        bobBearer = registerAndLogin("Ctx Bob", bobEmail);

        aliceOrg = createOrganization(aliceBearer, "Ctx Alice" + suffix);
        bobOrg = createOrganization(bobBearer, "Ctx Bob" + suffix);
        sharedOrg = createOrganization(aliceBearer, "Ctx Shared" + suffix);
        addMember(aliceBearer, sharedOrg, bobEmail, "VIEWER");
    }

    // --- sequencial (mesma thread) -------------------------------------------

    @Test
    void sequentialRequestsResolveTheirOwnTenantAndLeaveTheThreadClean() throws Exception {
        // requisições intercaladas na MESMA thread (a do teste): cada uma
        // grava o seu contexto e o remove ao terminar
        mockMvc.perform(get("/api/v1/organizations/" + aliceOrg)
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceOrg))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();

        mockMvc.perform(get("/api/v1/organizations/" + bobOrg)
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bobOrg))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();

        // caminho de FALHA (404 anti-enumeração) também limpa antes de responder
        mockMvc.perform(get("/api/v1/organizations/" + aliceOrg)
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
        assertThat(TenantContext.isActive()).as("failure must not leak").isFalse();

        // a requisição seguinte NÃO herda nada da anterior: Bob vê o SEU
        // papel (VIEWER) na organização compartilhada, não o ADMIN de antes
        mockMvc.perform(get("/api/v1/organizations/" + sharedOrg)
                        .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sharedOrg))
                .andExpect(jsonPath("$.role").value("VIEWER"));
        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();
    }

    // --- concorrência: tenants distintos -------------------------------------

    @Test
    void concurrentRequestsOfDifferentTenantsNeverShareContext() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> aliceRun = pool.submit(() -> {
                for (int i = 0; i < ITERATIONS; i++) {
                    mockMvc.perform(get("/api/v1/organizations/" + aliceOrg)
                                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(aliceOrg))
                            .andExpect(jsonPath("$.role").value("ADMIN"));

                    // o 404 dela (tenant alheio) roda na MESMA thread entre as
                    // duas acima: se vazasse, a próxima resposta viria com o
                    // id errado ou sem contexto
                    mockMvc.perform(get("/api/v1/organizations/" + bobOrg)
                                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.message").value("Resource not found"));

                    assertThat(TenantContext.isActive()).as("alice thread leak").isFalse();
                }
                return true;
            });

            Future<Boolean> bobRun = pool.submit(() -> {
                for (int i = 0; i < ITERATIONS; i++) {
                    mockMvc.perform(get("/api/v1/organizations/" + bobOrg)
                                    .header(HttpHeaders.AUTHORIZATION, bobBearer))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(bobOrg))
                            .andExpect(jsonPath("$.role").value("ADMIN"));

                    mockMvc.perform(get("/api/v1/organizations/" + aliceOrg)
                                    .header(HttpHeaders.AUTHORIZATION, bobBearer))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.message").value("Resource not found"));

                    assertThat(TenantContext.isActive()).as("bob thread leak").isFalse();
                }
                return true;
            });

            aliceRun.get(30, TimeUnit.SECONDS);
            bobRun.get(30, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();
    }

    // --- concorrência: MESMO tenant, papéis diferentes -----------------------

    @Test
    void concurrentRequestsOnSharedTenantResolveTheCallersOwnRole() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> adminRun = pool.submit(() -> {
                for (int i = 0; i < ITERATIONS; i++) {
                    mockMvc.perform(get("/api/v1/organizations/" + sharedOrg)
                                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(sharedOrg))
                            .andExpect(jsonPath("$.role").value("ADMIN"));
                    assertThat(TenantContext.isActive()).as("admin thread leak").isFalse();
                }
                return true;
            });

            Future<Boolean> viewerRun = pool.submit(() -> {
                for (int i = 0; i < ITERATIONS; i++) {
                    // mesmo tenant, outro usuário: se o contexto vazasse entre
                    // as threads, o VIEWER responderia ADMIN (ou vice-versa)
                    mockMvc.perform(get("/api/v1/organizations/" + sharedOrg)
                                    .header(HttpHeaders.AUTHORIZATION, bobBearer))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(sharedOrg))
                            .andExpect(jsonPath("$.role").value("VIEWER"));
                    assertThat(TenantContext.isActive()).as("viewer thread leak").isFalse();
                }
                return true;
            });

            adminRun.get(30, TimeUnit.SECONDS);
            viewerRun.get(30, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(TenantContext.isActive()).as("context must not leak").isFalse();
    }
}
