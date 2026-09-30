package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * API de métricas do monitor (Prompt 11):
 * {@code GET /monitors/{id}/metrics?range=24h}.
 *
 * <p>Cobertura: contrato do JSON (agregado, nunca linha crua nem
 * configuração), grade completa de buckets, default do {@code range} e
 * faixas explícitas, 400 de faixa desconhecida, leitura com qualquer papel
 * e com pais inativos, estado vazio sem inventar valor, e os bloqueios 404
 * da cadeia — inclusive a ORDEM contrato: cadeia do tenant antes da
 * validação de {@code range} (404 nunca vira 400). Os registros são
 * SEMEADOS DIRETO no repositório; a produção das linhas pelo pipeline é do
 * {@code MonitoringSchedulerTest}.</p>
 *
 * <p>O relógio é o congelado do {@link FixedMetricsClockConfig}: os
 * instantes exatos de {@code from}/{@code to} são parte do contrato
 * verificado aqui.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class MonitorMetricsApiTest extends AbstractMonitorIntegrationTest {

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

    /** 3 SUCCESS + 1 falha de alvo, todos no primeiro bucket da janela de 24h. */
    private void seedMixedChecks(String serviceId, String monitorId) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        for (int i = 0; i < 4; i++) {
            boolean healthy = i < 3;
            Instant startedAt = FROM_24H.plusSeconds(i);
            monitorCheckRepository.save(new MonitorCheck(
                    monitor,
                    startedAt,
                    startedAt.plusMillis(250),
                    250,
                    healthy ? 200 : null,
                    healthy ? CheckOutcome.SUCCESS : CheckOutcome.TARGET_FAILURE,
                    healthy ? null : CheckFailureCategory.TIMEOUT,
                    healthy ? "Target responded with HTTP 200"
                            : "Request exceeded the monitor timeout"));
        }
    }

    private String metricsUrl(Scene scene, String monitorId) {
        return monitorMetricsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);
    }

    // --- contrato -----------------------------------------------------------

    @Test
    void metricsReturnTheAggregatedContractNeverRawRowsOrConfig() throws Exception {
        Scene scene = newScene("mtr-api-contract", "Mtr Api Contract Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Contrato");
        seedMixedChecks(scene.serviceId(), monitorId);

        MvcResult result = mockMvc.perform(get(metricsUrl(scene, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorId").value(monitorId))
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.from").value(FROM_24H.toString()))
                .andExpect(jsonPath("$.to").value(TO_24H.toString()))
                .andExpect(jsonPath("$.bucketSeconds").value(3600))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.summary.totalChecks").value(4))
                .andExpect(jsonPath("$.summary.success").value(3))
                .andExpect(jsonPath("$.summary.degraded").value(0))
                .andExpect(jsonPath("$.summary.targetFailure").value(1))
                .andExpect(jsonPath("$.summary.sentinelInternalFailure").value(0))
                .andReturn();

        String json = result.getResponse().getContentAsString();

        // chave por chave: o cliente enxerga só o agregado
        Map<String, Object> body = responseBody(result);
        assertThat(body.keySet())
                .containsExactlyInAnyOrder(
                        "monitorId", "range", "from", "to",
                        "bucketSeconds", "summary", "buckets");
        assertThat(body)
                .doesNotContainKeys("serviceId", "projectId", "organizationId",
                        "url", "method", "active", "checks");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertThat(summary.keySet())
                .containsExactlyInAnyOrder(
                        "totalChecks", "success", "degraded", "targetFailure",
                        "sentinelInternalFailure", "availability", "successRate",
                        "avgLatencyMs", "minLatencyMs", "maxLatencyMs");
        assertThat(summary.get("availability")).isNotNull();
        assertThat(summary.get("avgLatencyMs")).isNotNull();
        assertThat(summary.get("minLatencyMs")).isNotNull();
        assertThat(summary.get("maxLatencyMs")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) body.get("buckets");
        Map<String, Object> firstBucket = buckets.get(0);
        assertThat(firstBucket.keySet())
                .containsExactlyInAnyOrder(
                        "bucketStart", "totalChecks", "success", "degraded",
                        "targetFailure", "sentinelInternalFailure", "availability",
                        "successRate", "avgLatencyMs", "minLatencyMs", "maxLatencyMs");
        assertThat(firstBucket.get("bucketStart")).isEqualTo(FROM_24H.toString());
        assertThat(firstBucket.get("totalChecks")).isEqualTo(4);

        Map<String, Object> quietBucket = buckets.get(1);
        assertThat(quietBucket.get("bucketStart"))
                .isEqualTo(FROM_24H.plusSeconds(3600).toString());
        assertThat(quietBucket.get("totalChecks")).isEqualTo(0);

        // fio do JSON: taxas com 2 casas; latência com avg 2 casas e min/max
        // inteiros em ms (todos os seeds deste teste têm 250ms — a prova do
        // filtro de outcome é do MonitorMetricsServiceTest)
        assertThat(json)
                .contains(
                        "\"availability\":75.00",
                        "\"successRate\":75.00",
                        "\"avgLatencyMs\":250.00",
                        "\"minLatencyMs\":250",
                        "\"maxLatencyMs\":250",
                        "\"availability\":null");
        // agregado nunca vaza linha crua nem configuração do monitor
        assertThat(json).doesNotContain("startedAt", "finishedAt", "\"checks\"", "\"url\"");
    }

    // --- range --------------------------------------------------------------

    @Test
    void rangeDefaultsToTwentyFourHoursAndHonoursExplicitFaixas() throws Exception {
        Scene scene = newScene("mtr-api-range", "Mtr Api Range Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Faixa");
        String url = metricsUrl(scene, monitorId);

        // ausente → default do servidor
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("24h"))
                .andExpect(jsonPath("$.bucketSeconds").value(3600))
                .andExpect(jsonPath("$.buckets.length()").value(24));

        // em branco → mesmo default
        mockMvc.perform(get(url + "?range=")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("24h"));

        // faixa explícita troca a grade inteira
        mockMvc.perform(get(url + "?range=1h")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("1h"))
                .andExpect(jsonPath("$.bucketSeconds").value(300))
                .andExpect(jsonPath("$.buckets.length()").value(12))
                .andExpect(jsonPath("$.from").value("2026-03-01T11:30:00Z"))
                .andExpect(jsonPath("$.to").value("2026-03-01T12:30:00Z"));

        mockMvc.perform(get(url + "?range=30d")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("30d"))
                .andExpect(jsonPath("$.bucketSeconds").value(86400))
                .andExpect(jsonPath("$.buckets.length()").value(30))
                .andExpect(jsonPath("$.from").value("2026-01-30T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-03-01T00:00:00Z"));
    }

    @Test
    void unknownRangeIsRejectedWithTheFieldValidationError() throws Exception {
        Scene scene = newScene("mtr-api-range400", "Mtr Api Range400 Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Faixa Ruim");

        mockMvc.perform(get(metricsUrl(scene, monitorId) + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("range: must be one of 1h, 6h, 24h, 7d, 30d"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").exists());
    }

    // --- estado vazio -------------------------------------------------------

    @Test
    void emptyWindowAnswersAStableGridWithNullRatesNotZero() throws Exception {
        Scene scene = newScene("mtr-api-empty", "Mtr Api Empty Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Sem Historico");

        MvcResult result = mockMvc.perform(get(metricsUrl(scene, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        // sem dado não vira 0% nem 100%: null explícito no fio do JSON
        assertThat(json)
                .contains(
                        "\"availability\":null",
                        "\"successRate\":null",
                        "\"avgLatencyMs\":null",
                        "\"minLatencyMs\":null",
                        "\"maxLatencyMs\":null");
    }

    // --- leitura livre: qualquer papel, pais inativos -----------------------

    @Test
    void metricsAreReadableByAnyRoleEvenWhenParentsAreInactive() throws Exception {
        Scene scene = newScene("mtr-api-role", "Mtr Api Role Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Somente Leitura");
        seedMixedChecks(scene.serviceId(), monitorId);
        String viewerEmail = uniqueEmail("mtr-viewer");
        String viewerBearer = registerAndLogin("Mtr Viewer", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");
        String url = metricsUrl(scene, monitorId);

        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, viewerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(4));

        // pais inativos continuam legíveis (mesmo arranjo dos endpoints de leitura)
        changeMonitorActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), monitorId, false);
        changeActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), false);

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(4));
    }

    // --- bloqueios da cadeia (404 genérico, anti-enumeração) ----------------

    @Test
    void metricsOfAnotherServiceOrMalformedIdReturnGeneric404() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mtr-api-cross"), "Mtr Api Cross Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mtr Api Cross");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");
        String own = createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Um");
        String alien = createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Dois");
        seedMixedChecks(second, alien);

        // monitor do serviço DOIS, apresentado sob o caminho do serviço UM
        mockMvc.perform(get(monitorMetricsUrl(
                                org.organizationId(), projectId, first, alien))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // id malformado nem chega ao repositório
        mockMvc.perform(get(monitorMetricsUrl(
                                org.organizationId(), projectId, first, "não-é-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // ORDEM contrato: cadeia (404) vale mais que range inválido (400)
        mockMvc.perform(get(monitorMetricsUrl(
                                        org.organizationId(), projectId, first, alien)
                                + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isNotFound());

        // controle: monitor válido e legível pelo caminho certo
        mockMvc.perform(get(monitorMetricsUrl(
                                org.organizationId(), projectId, first, own))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalChecks").value(0));
    }

    @Test
    void metricsOfAnotherTenantReturnGeneric404() throws Exception {
        Scene alpha = newScene("mtr-api-idor-alpha", "Mtr Api Alpha Corp");
        String monitorId = createMonitor(
                alpha.org().ownerBearer(), alpha.org().organizationId(),
                alpha.projectId(), alpha.serviceId(), "Alpha Mon");
        seedMixedChecks(alpha.serviceId(), monitorId);

        Scene beta = newScene("mtr-api-idor-beta", "Mtr Api Beta Corp");

        // dono do Beta não é membro do Alpha → 404 genérico, nunca 403
        mockMvc.perform(get(metricsUrl(alpha, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, beta.org().ownerBearer()))
                .andExpect(status().isNotFound());

        // mesmo com range inválido no mesmo aparente pedido: o 404 da cadeia
        // vem primeiro (anti-enumeração: o estranho não aprende o contrato)
        mockMvc.perform(get(metricsUrl(alpha, monitorId) + "?range=48h")
                        .header(HttpHeaders.AUTHORIZATION, beta.org().ownerBearer()))
                .andExpect(status().isNotFound());
    }

    // --- autenticação / superfície ------------------------------------------

    @Test
    void metricsWithoutAuthenticationReturn401() throws Exception {
        Scene scene = newScene("mtr-api-auth", "Mtr Api Auth Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Protegido");
        seedMixedChecks(scene.serviceId(), monitorId);

        mockMvc.perform(get(metricsUrl(scene, monitorId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsRejectWriteMethodsOnTheReadOnlyRoute() throws Exception {
        Scene scene = newScene("mtr-api-method", "Mtr Api Method Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Somente GET");

        mockMvc.perform(post(metricsUrl(scene, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed());
    }
}
