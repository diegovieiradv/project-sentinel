package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * API de histórico de checagens (Prompt 10): {@code GET /monitors/{id}/checks}.
 *
 * <p>Cobertura: contrato do DTO (só resultado, nunca configuração), ordem
 * newest-first, paginação e limites, escopo por monitor, leitura com qualquer
 * papel e com pais inativos, histórico vazio e os bloqueios 404 da cadeia
 * (monitor de outro serviço, id malformado, tenant alheio) e 400 de
 * paginação. Os registros são SEMEADOS DIRETO no repositório (não pelo
 * executor): este teste valida a LEITURA da API; a produção das linhas pelo
 * pipeline é do {@code MonitoringSchedulerTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorCheckApiTest extends AbstractMonitorIntegrationTest {

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

    /** Base dos instantes: fixa, ordenada por construção. */
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    /**
     * Semeia {@code count} checagens para o monitor, do mais antigo para o
     * mais novo: índice par = 200 saudável, índimpar = timeout.
     *
     * @return os ids na ordem de escrita (ou seja, mais antigo primeiro)
     */
    private List<String> seedChecks(String serviceId, String monitorId, int count) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean healthy = i % 2 == 0;
            Instant startedAt = T0.plusSeconds(i);
            ids.add(monitorCheckRepository
                    .save(new MonitorCheck(
                            monitor,
                            startedAt,
                            startedAt.plusMillis(250),
                            250,
                            healthy ? 200 : null,
                            healthy ? CheckOutcome.SUCCESS : CheckOutcome.TARGET_FAILURE,
                            healthy ? null : CheckFailureCategory.TIMEOUT,
                            healthy ? "Target responded with HTTP 200" : "Request exceeded the monitor timeout"))
                    .getId()
                    .toString());
        }
        return ids;
    }

    // --- caminho feliz ------------------------------------------------------

    @Test
    void historyReturnsNewestFirstWithThePersistedContract() throws Exception {
        Scene scene = newScene("chk-owner-read", "Chk Read Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Historico");
        List<String> seeded = seedChecks(scene.serviceId(), monitorId, 5);

        MvcResult result = mockMvc.perform(get(monitorChecksUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) responseBody(result).get("content");

        // ordem de contrato: mais novo primeiro (inverso da ordem de escrita)
        assertThat(content)
                .extracting(item -> item.get("id"))
                .containsExactly(
                        seeded.get(4), seeded.get(3), seeded.get(2), seeded.get(1), seeded.get(0));

        Map<String, Object> newest = content.get(0);
        assertThat(newest.keySet())
                .containsExactlyInAnyOrder(
                        "id", "monitorId", "startedAt", "finishedAt", "durationMillis",
                        "httpStatus", "outcome", "failureCategory", "message", "createdAt");
        assertThat(newest)
                .doesNotContainKeys("serviceId", "projectId", "organizationId", "url", "method");
        assertThat(newest.get("monitorId")).isEqualTo(monitorId);
        assertThat(newest.get("outcome")).isEqualTo("SUCCESS");
        assertThat(newest.get("failureCategory")).isNull();
        assertThat(newest.get("httpStatus")).isEqualTo(200);
        assertThat(newest.get("durationMillis")).isEqualTo(250);
        assertThat(newest.get("startedAt")).isEqualTo(T0.plusSeconds(4).toString());

        // falha de rede: sem status HTTP, categoria explícita
        Map<String, Object> unreachable = content.get(1);
        assertThat(unreachable.get("outcome")).isEqualTo("TARGET_FAILURE");
        assertThat(unreachable.get("failureCategory")).isEqualTo("TIMEOUT");
        assertThat(unreachable.get("httpStatus")).isNull();
    }

    @Test
    void historyIsScopedToItsOwnMonitorOnly() throws Exception {
        Scene scene = newScene("chk-owner-scope", "Chk Scope Corp");
        String first = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Primeiro");
        String second = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Segundo");
        seedChecks(scene.serviceId(), first, 3);
        seedChecks(scene.serviceId(), second, 7);

        MvcResult result = mockMvc.perform(get(monitorChecksUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), first))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) responseBody(result).get("content");
        assertThat(content)
                .extracting(item -> item.get("monitorId"))
                .containsOnly(first);
    }

    @Test
    void emptyHistoryIsAnEmptyPageNotAnError() throws Exception {
        Scene scene = newScene("chk-owner-empty", "Chk Empty Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Sem Historico");

        mockMvc.perform(get(monitorChecksUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- paginação ----------------------------------------------------------

    @Test
    void historyDefaultsToTwentyAndRejectsOutOfRangePageRequest() throws Exception {
        Scene scene = newScene("chk-owner-page", "Chk Page Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Paginado");
        seedChecks(scene.serviceId(), monitorId, 25);
        String url = monitorChecksUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content.length()").value(20));

        mockMvc.perform(get(url + "?page=1&size=20")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content.length()").value(5));

        mockMvc.perform(get(url + "?size=0")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(url + "?size=101")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(url + "?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest());
    }

    // --- leitura livre: qualquer papel, pais inativos -----------------------

    @Test
    void historyIsReadableByAnyRoleEvenWhenParentsAreInactive() throws Exception {
        Scene scene = newScene("chk-owner-role", "Chk Role Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Somente Leitura");
        seedChecks(scene.serviceId(), monitorId, 2);
        String viewerEmail = uniqueEmail("chk-viewer");
        String viewerBearer = registerAndLogin("Chk Viewer", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");
        String url = monitorChecksUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // pais inativos continuam legíveis (mesmo arranjo do GET do monitor)
        changeMonitorActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), monitorId, false);
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // --- bloqueios da cadeia (404 genérico, anti-enumeração) ----------------

    @Test
    void monitorFromAnotherServiceInSameTenantReturnsGeneric404() throws Exception {
        Fixture org = newOrganization(uniqueEmail("chk-owner-cross"), "Chk Cross Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Chk Cross");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");
        String own = createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Um");
        String alien = createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Dois");
        seedChecks(second, alien, 1);

        // monitor do serviço DOIS, apresentado sob o caminho do serviço UM
        mockMvc.perform(get(monitorChecksUrl(
                                org.organizationId(), projectId, first, alien))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // id malformado nem chega ao repositório
        mockMvc.perform(get(monitorChecksUrl(org.organizationId(), projectId, first, "não-é-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // monitor válido e legível pelo caminho certo (controle do teste)
        mockMvc.perform(get(monitorChecksUrl(org.organizationId(), projectId, first, own))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void historyOfAnotherTenantReturnsGeneric404() throws Exception {
        Scene alpha = newScene("chk-idor-alpha", "Chk Alpha Corp");
        String monitorId = createMonitor(
                alpha.org().ownerBearer(), alpha.org().organizationId(),
                alpha.projectId(), alpha.serviceId(), "Alpha Mon");
        seedChecks(alpha.serviceId(), monitorId, 1);

        Scene beta = newScene("chk-idor-beta", "Chk Beta Corp");

        // dono do Beta não é membro do Alpha → 404 genérico, nunca 403
        mockMvc.perform(get(monitorChecksUrl(
                                alpha.org().organizationId(), alpha.projectId(),
                                alpha.serviceId(), monitorId))
                        .header(HttpHeaders.AUTHORIZATION, beta.org().ownerBearer()))
                .andExpect(status().isNotFound());
    }

    // --- autenticação / superfície ------------------------------------------

    @Test
    void historyWithoutAuthenticationReturns401() throws Exception {
        Scene scene = newScene("chk-owner-auth", "Chk Auth Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Protegido");
        seedChecks(scene.serviceId(), monitorId, 1);

        mockMvc.perform(get(monitorChecksUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), monitorId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void historyRejectsWriteMethodsOnTheReadOnlyRoute() throws Exception {
        Scene scene = newScene("chk-owner-method", "Chk Method Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Somente GET");

        mockMvc.perform(post(monitorChecksUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed());
    }
}
