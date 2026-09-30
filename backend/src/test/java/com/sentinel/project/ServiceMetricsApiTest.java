package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * API de métricas do serviço (Prompt 11):
 * {@code GET /services/{id}/metrics?range=24h}.
 *
 * <p>Cobertura: contrato das TRÊS camadas (resumo + série + breakdown por
 * monitor, sempre sobre a mesma janela), união de TODOS os monitores —
 * inclusive dos desligados — ordenação do breakdown por nome, ausência de
 * monitores sem check na janela, faixas, 400 de faixa desconhecida, 404 da
 * cadeia ANTES da validação de range, leitura livre com pais inativos, 401
 * e 405. Os registros são SEMEADOS DIRETO no repositório.</p>
 *
 * <p>Relógio congelado pelo {@link FixedMetricsClockConfig} — os instantes
 * de {@code from}/{@code to} fazem parte do contrato verificado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class ServiceMetricsApiTest extends AbstractMonitorIntegrationTest {

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

    /** Janela de 24h do relógio congelado (12:34:56 truncado para 12:00). */
    private static final Instant TO_24H = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant FROM_24H = Instant.parse("2026-02-28T12:00:00Z");

    /** {@code count} checks no primeiro bucket da janela de 24h. */
    private void seedChecks(
            String serviceId, String monitorId, int count, boolean healthy, long durationMillis) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        for (int i = 0; i < count; i++) {
            Instant startedAt = FROM_24H.plusSeconds(i);
            monitorCheckRepository.save(new MonitorCheck(
                    monitor,
                    startedAt,
                    startedAt.plusMillis(durationMillis),
                    durationMillis,
                    healthy ? 200 : null,
                    healthy ? CheckOutcome.SUCCESS : CheckOutcome.TARGET_FAILURE,
                    healthy ? null : CheckFailureCategory.TIMEOUT,
                    healthy ? "Target responded with HTTP 200"
                            : "Request exceeded the monitor timeout"));
        }
    }

    /** Um check num instante EXPLÍCITO (para cair dentro de faixas menores). */
    private void seedAt(
            String serviceId, String monitorId, Instant startedAt, long durationMillis) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        monitorCheckRepository.save(new MonitorCheck(
                monitor,
                startedAt,
                startedAt.plusMillis(durationMillis),
                durationMillis,
                200,
                CheckOutcome.SUCCESS,
                null,
                "Target responded with HTTP 200"));
    }

    private String metricsUrl(Scene scene) {
        return serviceMetricsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());
    }

    // --- contrato -----------------------------------------------------------

    @Test
    void serviceMetricsAggregateEveryMonitorInThreeLayers() throws Exception {
        Scene scene = newScene("svc-api-contract", "Svc Api Contract Corp");
        String alpha = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Alpha");
        String beta = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Beta");
        String gamma = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Gamma");

        seedChecks(scene.serviceId(), alpha, 3, true, 250);
        seedChecks(scene.serviceId(), beta, 2, false, 300);
        // Gamma fica sem nenhum check na janela

        // histórico não é apagado ao desligar: monitor inativo segue contando
        changeMonitorActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), beta, false);

        MvcResult result = mockMvc.perform(get(metricsUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.from").value(FROM_24H.toString()))
                .andExpect(jsonPath("$.to").value(TO_24H.toString()))
                .andExpect(jsonPath("$.bucketSeconds").value(3600))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.summary.totalChecks").value(5))
                .andExpect(jsonPath("$.summary.success").value(3))
                .andExpect(jsonPath("$.summary.targetFailure").value(2))
                .andExpect(jsonPath("$.monitors.length()").value(2))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Map<String, Object> body = responseBody(result);

        // chave por chave: três camadas, nunca linha crua nem configuração
        assertThat(body.keySet())
                .containsExactlyInAnyOrder(
                        "serviceId", "range", "from", "to",
                        "bucketSeconds", "summary", "buckets", "monitors");
        assertThat(body)
                .doesNotContainKeys("name", "description", "status",
                        "url", "checks", "projectId", "organizationId");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> monitors = (List<Map<String, Object>>) body.get("monitors");
        assertThat(monitors)
                .extracting(row -> row.get("monitorName"))
                .as("breakdown ordenado por nome; monitor sem check fica de fora")
                .containsExactly("Alpha", "Beta");
        Map<String, Object> first = monitors.get(0);
        assertThat(first.keySet())
                .containsExactlyInAnyOrder(
                        "monitorId", "monitorName", "totalChecks", "success", "degraded",
                        "targetFailure", "sentinelInternalFailure", "availability",
                        "successRate", "avgLatencyMs", "minLatencyMs", "maxLatencyMs");
        assertThat(first.get("monitorId")).isEqualTo(alpha);
        assertThat(first.get("totalChecks")).isEqualTo(3);
        assertThat(monitors.get(1).get("monitorId")).isEqualTo(beta);
        assertThat(monitors.get(1).get("totalChecks")).isEqualTo(2);
        assertThat(monitors.get(1).get("avgLatencyMs"))
                .as("Beta só teve TARGET_FAILURE → latência null no breakdown")
                .isNull();
        assertThat(monitors.get(1).get("minLatencyMs")).isNull();
        assertThat(monitors.get(1).get("maxLatencyMs")).isNull();

        // fio do JSON: escala de 2 casas em cada camada; latência SÓ dos
        // 3 SUCCESS da Alpha (250ms) — as falhas da Beta (300ms) ficam fora
        assertThat(json)
                .contains(
                        "\"availability\":60.00",
                        "\"successRate\":60.00",
                        "\"availability\":100.00",
                        "\"availability\":0.00",
                        "\"avgLatencyMs\":250.00",
                        "\"minLatencyMs\":250",
                        "\"maxLatencyMs\":250");
        // soma do breakdown fecha com o resumo, e a série também
        long breakdownTotal = monitors.stream()
                .mapToLong(row -> ((Number) row.get("totalChecks")).longValue())
                .sum();
        assertThat(breakdownTotal).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) body.get("buckets");
        long seriesTotal = buckets.stream()
                .mapToLong(row -> ((Number) row.get("totalChecks")).longValue())
                .sum();
        assertThat(seriesTotal).isEqualTo(5);
        assertThat(json).doesNotContain("startedAt", "finishedAt", "\"checks\"");
    }

    // --- range --------------------------------------------------------------

    @Test
    void rangeDefaultsAndExplicitFaixasApplyToEveryLayer() throws Exception {
        Scene scene = newScene("svc-api-range", "Svc Api Range Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Faixa");
        // dois checks antigos: dentro da janela de 24h, FORA da de 1h
        seedChecks(scene.serviceId(), monitorId, 2, true, 100);
        // um check na própria manhã do relógio congelado: dentro das DUAS
        seedAt(scene.serviceId(), monitorId, Instant.parse("2026-03-01T11:45:00Z"), 100);
        String url = metricsUrl(scene);

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.bucketSeconds").value(3600))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.summary.totalChecks").value(3))
                .andExpect(jsonPath("$.monitors.length()").value(1));

        mockMvc.perform(get(url + "?range=1h")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("1h"))
                .andExpect(jsonPath("$.bucketSeconds").value(300))
                .andExpect(jsonPath("$.buckets.length()").value(12))
                .andExpect(jsonPath("$.from").value("2026-03-01T11:30:00Z"))
                .andExpect(jsonPath("$.to").value("2026-03-01T12:30:00Z"))
                // breakdown e resumo recortam a MESMA janela da série
                .andExpect(jsonPath("$.summary.totalChecks").value(1))
                .andExpect(jsonPath("$.monitors.length()").value(1));
    }

    @Test
    void unknownRangeIsRejectedWithTheFieldValidationError() throws Exception {
        Scene scene = newScene("svc-api-range400", "Svc Api Range400 Corp");

        mockMvc.perform(get(metricsUrl(scene) + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("range: must be one of 1h, 6h, 24h, 7d, 30d"));
    }

    // --- estado vazio -------------------------------------------------------

    @Test
    void emptyServiceKeepsTheGridAndAnEmptyBreakdown() throws Exception {
        Scene scene = newScene("svc-api-empty", "Svc Api Empty Corp");
        createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Sem Checks");

        MvcResult result = mockMvc.perform(get(metricsUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.monitors").isArray())
                .andExpect(jsonPath("$.monitors.length()").value(0))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        // sem dado não vira 0% nem 100%: null explícito no fio do JSON
        assertThat(json)
                .contains(
                        "\"availability\":null", "\"successRate\":null",
                        "\"avgLatencyMs\":null", "\"minLatencyMs\":null",
                        "\"maxLatencyMs\":null");
    }

    // --- leitura livre: qualquer papel, pais inativos -----------------------

    @Test
    void metricsAreReadableByAnyRoleEvenWhenParentsAreInactive() throws Exception {
        Scene scene = newScene("svc-api-role", "Svc Api Role Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Somente Leitura");
        seedChecks(scene.serviceId(), monitorId, 2, true, 100);
        String viewerEmail = uniqueEmail("svc-viewer");
        String viewerBearer = registerAndLogin("Svc Viewer", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        mockMvc.perform(get(metricsUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(2));

        // pais inativos continuam legíveis
        changeMonitorActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), monitorId, false);
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(get(metricsUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(2));
    }

    // --- bloqueios da cadeia (404 genérico, anti-enumeração) ----------------

    @Test
    void metricsOfAnotherServiceOrMalformedIdReturnGeneric404() throws Exception {
        Fixture org = newOrganization(uniqueEmail("svc-api-cross"), "Svc Api Cross Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Svc Api Cross");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");
        // serviço legítimo, mas de OUTRO projeto do mesmo tenant
        String otherProject =
                createProject(org.ownerBearer(), org.organizationId(), "Projeto Outro");
        String alien = createService(org.ownerBearer(), org.organizationId(), otherProject, "Alheio");
        String monitorId = createMonitor(
                org.ownerBearer(), org.organizationId(), projectId, second, "Mon");
        seedChecks(second, monitorId, 1, true, 100);

        // serviço de OUTRO projeto apresentado sob o caminho DESTE projeto
        mockMvc.perform(get(serviceMetricsUrl(org.organizationId(), projectId, alien))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // id malformado nem chega ao repositório
        mockMvc.perform(get(serviceMetricsUrl(org.organizationId(), projectId, "não-é-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // ORDEM contrato: cadeia (404) vale mais que range inválido (400) —
        // serviço inexistente no projeto + faixa ruim no MESMO aparente pedido
        mockMvc.perform(get(serviceMetricsUrl(
                                        org.organizationId(), projectId,
                                        UUID.randomUUID().toString())
                                + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // controle: caminho certo responde com a janela normal
        mockMvc.perform(get(serviceMetricsUrl(org.organizationId(), projectId, second))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(1));

        // e o serviço "Um" (sem check) também responde, com janela vazia
        mockMvc.perform(get(serviceMetricsUrl(org.organizationId(), projectId, first))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(0));
    }

    @Test
    void metricsOfAnotherTenantReturnGeneric404() throws Exception {
        Scene alpha = newScene("svc-api-idor-alpha", "Svc Api Alpha Corp");
        String monitorId = createMonitor(
                alpha.org().ownerBearer(), alpha.org().organizationId(),
                alpha.projectId(), alpha.serviceId(), "Alpha Mon");
        seedChecks(alpha.serviceId(), monitorId, 1, true, 100);

        Scene beta = newScene("svc-api-idor-beta", "Svc Api Beta Corp");

        // dono do Beta não é membro do Alpha → 404 genérico, nunca 403
        mockMvc.perform(get(metricsUrl(alpha))
                        .header(HttpHeaders.AUTHORIZATION, beta.org().ownerBearer()))
                .andExpect(status().isNotFound());

        // mesmo com range inválido: o 404 da cadeia vem primeiro
        mockMvc.perform(get(metricsUrl(alpha) + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, beta.org().ownerBearer()))
                .andExpect(status().isNotFound());
    }

    // --- autenticação / superfície ------------------------------------------

    @Test
    void metricsWithoutAuthenticationReturn401() throws Exception {
        Scene scene = newScene("svc-api-auth", "Svc Api Auth Corp");

        mockMvc.perform(get(metricsUrl(scene)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsRejectWriteMethodsOnTheReadOnlyRoute() throws Exception {
        Scene scene = newScene("svc-api-method", "Svc Api Method Corp");

        mockMvc.perform(post(metricsUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed());
    }
}
