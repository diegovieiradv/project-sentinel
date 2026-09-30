package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Membership;
import com.sentinel.organization.repository.MembershipRepository;
import com.sentinel.project.monitor.dto.MetricsBucket;
import com.sentinel.project.monitor.dto.MonitorMetricsResponse;
import com.sentinel.project.monitor.dto.ServiceMetricsBreakdown;
import com.sentinel.project.monitor.dto.ServiceMetricsResponse;
import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;
import com.sentinel.project.monitor.service.MonitorMetricsService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Métricas históricas (Prompt 11) na camada de serviço: janela, grade de
 * buckets, fórmulas e cadeia de tenant — sem HTTP por cima (o contrato de
 * JSON é do {@code MonitorMetricsApiTest}/{@code ServiceMetricsApiTest}).
 *
 * <p>Cenários SEMEADOS DIRETO no repositório (mesmo arranjo do
 * {@code MonitorCheckApiTest}): aqui se valida o CÁLCULO sobre linhas já
 * gravadas; a produção das linhas pelo pipeline é do
 * {@code MonitoringSchedulerTest}. O relógio é o congelado do
 * {@link FixedMetricsClockConfig} — nenhuma asserção depende de tempo
 * real.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class MonitorMetricsServiceTest extends AbstractMonitorIntegrationTest {

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

    @Autowired
    private MonitorMetricsService monitorMetricsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    // --- semeadura e atalhos ------------------------------------------------

    private void seed(
            String serviceId,
            String monitorId,
            Instant startedAt,
            CheckOutcome outcome,
            long durationMillis) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        monitorCheckRepository.save(new MonitorCheck(
                monitor,
                startedAt,
                startedAt.plusMillis(durationMillis),
                durationMillis,
                outcome == CheckOutcome.SUCCESS || outcome == CheckOutcome.DEGRADED_SIGNAL
                        ? 200
                        : null,
                outcome,
                outcome == CheckOutcome.TARGET_FAILURE ? CheckFailureCategory.TIMEOUT : null,
                "seeded check"));
    }

    private MonitorMetricsResponse metricsOf(Scene scene, String monitorId, String range) {
        return inTenant(scene.org(), () -> monitorMetricsService.monitorMetrics(
                UUID.fromString(scene.projectId()),
                UUID.fromString(scene.serviceId()),
                UUID.fromString(monitorId),
                range));
    }

    private ServiceMetricsResponse serviceMetricsOf(Scene scene, String range) {
        return inTenant(scene.org(), () -> monitorMetricsService.serviceMetrics(
                UUID.fromString(scene.projectId()), UUID.fromString(scene.serviceId()), range));
    }

    /**
     * Chama o service COM o mesmo contexto que o {@code TenantInterceptor}
     * montaria numa requisição {@code @TenantScoped}: a camada de serviço lê
     * o tenant da ThreadLocal, que fora de uma requisição está vazia (daí o
     * {@code TENANT_REQUIRED} "Organization context required"). O
     * {@code finally} devolve a thread limpa — os testes de isolamento
     * (TenantContextIsolationTest) exigem que nada vaze.
     */
    private <T> T inTenant(Fixture org, Supplier<T> call) {
        try {
            User user = userRepository.findByEmail(org.ownerEmail()).orElseThrow();
            Membership membership = membershipRepository
                    .findByUserIdAndOrganizationId(
                            user.getId(), UUID.fromString(org.organizationId()))
                    .orElseThrow();
            TenantContext.set(new TenantContext.Context(
                    user, membership.getOrganization(), membership, membership.getRole()));
            return call.get();
        }
        finally {
            TenantContext.clear();
        }
    }

    // --- janela -------------------------------------------------------------

    @Test
    void windowIsHalfOpenAndCoversCompleteBucketsOnly() throws Exception {
        Scene scene = newScene("mtr-window", "Mtr Window Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Janela");

        // dentro da janela [from, to)
        seed(scene.serviceId(), monitorId, FROM_24H, CheckOutcome.SUCCESS, 100);
        seed(scene.serviceId(), monitorId, TO_24H.minusSeconds(1), CheckOutcome.SUCCESS, 100);
        // EXATAMENTE no fim da janela → pertence ao período seguinte
        seed(scene.serviceId(), monitorId, TO_24H, CheckOutcome.SUCCESS, 100);
        // um segundo antes do início → pertence ao período anterior
        seed(scene.serviceId(), monitorId, FROM_24H.minusSeconds(1), CheckOutcome.SUCCESS, 100);

        MonitorMetricsResponse response = metricsOf(scene, monitorId, null);

        assertThat(response.range()).isEqualTo("24h");
        assertThat(response.from()).isEqualTo(FROM_24H);
        assertThat(response.to()).isEqualTo(TO_24H);
        assertThat(response.bucketSeconds()).isEqualTo(3600);
        assertThat(response.summary().totalChecks())
                .as("meia-aberta: só os dois checks dentro de [from, to)")
                .isEqualTo(2);

        assertThat(response.buckets()).hasSize(24);
        assertThat(response.buckets().get(0).bucketStart()).isEqualTo(FROM_24H);
        assertThat(response.buckets().get(23).bucketStart())
                .as("último bucket completo começa 1h antes do fim da janela")
                .isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
        // os dois checks que contam caem em buckets distintos, com 1 check cada
        assertThat(response.buckets())
                .filteredOn(bucket -> bucket.totalChecks() > 0)
                .extracting(MetricsBucket::totalChecks)
                .containsExactly(1L, 1L);
    }

    // --- fórmulas -----------------------------------------------------------

    @Test
    void availabilityAndSuccessRateExcludeInternalFailures() throws Exception {
        Scene scene = newScene("mtr-formula", "Mtr Formula Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Formula");

        for (int i = 0; i < 8; i++) {
            seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(i), CheckOutcome.SUCCESS, 100);
        }
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(8), CheckOutcome.DEGRADED_SIGNAL, 200);
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(9), CheckOutcome.TARGET_FAILURE, 300);
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(10), CheckOutcome.SENTINEL_INTERNAL_FAILURE, 400);

        var summary = metricsOf(scene, monitorId, null).summary();

        assertThat(summary.totalChecks()).isEqualTo(11);
        assertThat(summary.success()).isEqualTo(8);
        assertThat(summary.degraded()).isEqualTo(1);
        assertThat(summary.targetFailure()).isEqualTo(1);
        assertThat(summary.sentinelInternalFailure()).isEqualTo(1);
        // 9 saudáveis / 10 relevantes = 90.00; 8 sucessos / 10 = 80.00
        assertThat(summary.availability()).isEqualByComparingTo("90.00");
        assertThat(summary.successRate()).isEqualByComparingTo("80.00");
        // latência SÓ sobre SUCCESS/DEGRADED: (8×100 + 200) / 9 = 111.11
        assertThat(summary.avgLatencyMs()).isEqualByComparingTo("111.11");
        // falhas ficam FORA da latência: max 200 < TARGET 300 < INTERNAL 400
        assertThat(summary.minLatencyMs()).isEqualTo(100L);
        assertThat(summary.maxLatencyMs()).isEqualTo(200L);
    }

    @Test
    void ratesAreRoundedHalfUpToTwoDecimals() throws Exception {
        Scene scene = newScene("mtr-round", "Mtr Round Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Arredondamento");

        seed(scene.serviceId(), monitorId, FROM_24H, CheckOutcome.SUCCESS, 100);
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(1), CheckOutcome.DEGRADED_SIGNAL, 100);
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(2), CheckOutcome.TARGET_FAILURE, 100);

        var summary = metricsOf(scene, monitorId, null).summary();

        // 2/3 = 66,666… → 66.67 e 1/3 = 33,333… → 33.33 (HALF_UP, 2 casas)
        assertThat(summary.availability()).isEqualByComparingTo("66.67");
        assertThat(summary.successRate()).isEqualByComparingTo("33.33");
        assertThat(summary.availability().scale()).isEqualTo(2);
        assertThat(summary.successRate().scale()).isEqualTo(2);
    }

    @Test
    void emptyWindowKeepsTheWholeGridWithNullRates() throws Exception {
        Scene scene = newScene("mtr-empty", "Mtr Empty Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Vazio");

        MonitorMetricsResponse response = metricsOf(scene, monitorId, null);

        assertThat(response.summary().totalChecks()).isZero();
        assertThat(response.summary().availability())
                .as("sem dado não é 0% nem 100%: é null")
                .isNull();
        assertThat(response.summary().successRate()).isNull();
        assertThat(response.summary().avgLatencyMs()).isNull();
        assertThat(response.summary().minLatencyMs()).isNull();
        assertThat(response.summary().maxLatencyMs()).isNull();

        assertThat(response.buckets()).hasSize(24);
        assertThat(response.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.totalChecks()).isZero();
            assertThat(bucket.availability()).isNull();
            assertThat(bucket.successRate()).isNull();
            assertThat(bucket.avgLatencyMs()).isNull();
            assertThat(bucket.minLatencyMs()).isNull();
            assertThat(bucket.maxLatencyMs()).isNull();
        });
    }

    @Test
    void internalFailuresOnlyNeverInventAValue() throws Exception {
        Scene scene = newScene("mtr-internal", "Mtr Internal Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Só Interno");

        seed(scene.serviceId(), monitorId, FROM_24H, CheckOutcome.SENTINEL_INTERNAL_FAILURE, 50);
        seed(scene.serviceId(), monitorId, FROM_24H.plusSeconds(1), CheckOutcome.SENTINEL_INTERNAL_FAILURE, 70);

        var summary = metricsOf(scene, monitorId, null).summary();

        assertThat(summary.totalChecks()).isEqualTo(2);
        assertThat(summary.sentinelInternalFailure()).isEqualTo(2);
        assertThat(summary.availability())
                .as("nenhum check relevante → sem disponibilidade a reportar")
                .isNull();
        assertThat(summary.successRate()).isNull();
        assertThat(summary.avgLatencyMs())
                .as("sem SUCCESS/DEGRADED não há latência de resposta: null, nunca 0")
                .isNull();
        assertThat(summary.minLatencyMs()).isNull();
        assertThat(summary.maxLatencyMs()).isNull();
    }

    // --- faixas -------------------------------------------------------------

    @Test
    void eachRangeDeclaresItsOwnGrid() throws Exception {
        Scene scene = newScene("mtr-range", "Mtr Range Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Faixas");

        MonitorMetricsResponse oneHour = metricsOf(scene, monitorId, "1h");
        assertThat(oneHour.range()).isEqualTo("1h");
        assertThat(oneHour.bucketSeconds()).isEqualTo(300);
        assertThat(oneHour.from()).isEqualTo(Instant.parse("2026-03-01T11:30:00Z"));
        assertThat(oneHour.to()).isEqualTo(Instant.parse("2026-03-01T12:30:00Z"));
        assertThat(oneHour.buckets()).hasSize(12);
        assertThat(oneHour.buckets().get(11).bucketStart())
                .isEqualTo(Instant.parse("2026-03-01T12:25:00Z"));

        MonitorMetricsResponse sixHours = metricsOf(scene, monitorId, "6h");
        assertThat(sixHours.bucketSeconds()).isEqualTo(900);
        assertThat(sixHours.from()).isEqualTo(Instant.parse("2026-03-01T06:30:00Z"));
        assertThat(sixHours.buckets()).hasSize(24);

        MonitorMetricsResponse sevenDays = metricsOf(scene, monitorId, "7d");
        assertThat(sevenDays.bucketSeconds()).isEqualTo(21600);
        assertThat(sevenDays.to()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
        assertThat(sevenDays.from()).isEqualTo(Instant.parse("2026-02-22T12:00:00Z"));
        assertThat(sevenDays.buckets()).hasSize(28);

        MonitorMetricsResponse thirtyDays = metricsOf(scene, monitorId, "30d");
        assertThat(thirtyDays.bucketSeconds()).isEqualTo(86400);
        assertThat(thirtyDays.to()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(thirtyDays.from()).isEqualTo(Instant.parse("2026-01-30T00:00:00Z"));
        assertThat(thirtyDays.buckets()).hasSize(30);
        assertThat(thirtyDays.buckets().get(0).bucketStart()).isEqualTo(thirtyDays.from());
        assertThat(thirtyDays.buckets().get(29).bucketStart())
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    @Test
    void unknownRangeIsRejectedAfterTheChainIsProven() throws Exception {
        Scene scene = newScene("mtr-range400", "Mtr Range400 Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Faixa Ruim");

        ApiException exception = catchThrowableOfType(
                () -> metricsOf(scene, monitorId, "48h"), ApiException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessage("range: must be one of 1h, 6h, 24h, 7d, 30d");
    }

    // --- cadeia / escopo ----------------------------------------------------

    @Test
    void metricsRespectTheTenantChainWithGeneric404() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mtr-chain"), "Mtr Chain Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Mtr Chain");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Dois");
        String own = createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Proprio");
        String alien = createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Alheio");
        seed(second, alien, FROM_24H, CheckOutcome.SUCCESS, 100);

        // monitor do serviço DOIS apresentado sob o caminho do serviço UM
        ApiException wrongService = catchThrowableOfType(
                () -> inTenant(org, () -> monitorMetricsService.monitorMetrics(
                        UUID.fromString(projectId), UUID.fromString(first),
                        UUID.fromString(alien), null)),
                ApiException.class);
        assertThat(wrongService.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(wrongService.getErrorCode()).isEqualTo("NOT_FOUND");
        assertThat(wrongService).hasMessage("Resource not found");

        // controle: caminho certo responde com a janela normal
        MonitorMetricsResponse ok = inTenant(org, () -> monitorMetricsService.monitorMetrics(
                UUID.fromString(projectId), UUID.fromString(first),
                UUID.fromString(own), null));
        assertThat(ok.summary().totalChecks()).isZero();

        // serviço inexistente no projeto → mesmo 404 genérico
        ApiException missingService = catchThrowableOfType(
                () -> inTenant(org, () -> monitorMetricsService.serviceMetrics(
                        UUID.fromString(projectId), UUID.randomUUID(), null)),
                ApiException.class);
        assertThat(missingService.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);

        // organização alheia no contexto, projeto do Alpha no caminho →
        // a cadeia quebra no projeto (404 genérico, nunca 403)
        Fixture beta = newOrganization(uniqueEmail("mtr-idor"), "Mtr Idor Corp");
        ApiException otherTenant = catchThrowableOfType(
                () -> inTenant(beta, () -> monitorMetricsService.monitorMetrics(
                        UUID.fromString(projectId), UUID.fromString(first),
                        UUID.fromString(own), null)),
                ApiException.class);
        assertThat(otherTenant.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- serviço ------------------------------------------------------------

    @Test
    void serviceMetricsAggregateEveryMonitorIncludingInactiveOnes() throws Exception {
        Scene scene = newScene("mtr-service", "Mtr Service Corp");
        String alpha = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Alpha");
        String beta = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Beta");
        String gamma = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Gamma");

        for (int i = 0; i < 3; i++) {
            seed(scene.serviceId(), alpha, FROM_24H.plusSeconds(i), CheckOutcome.SUCCESS, 100);
        }
        seed(scene.serviceId(), beta, FROM_24H.plusSeconds(10), CheckOutcome.TARGET_FAILURE, 300);
        seed(scene.serviceId(), beta, FROM_24H.plusSeconds(11), CheckOutcome.TARGET_FAILURE, 300);

        // histórico não é apagado: monitor desligado continua contando
        changeMonitorActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), beta, false);

        ServiceMetricsResponse response = serviceMetricsOf(scene, null);

        assertThat(response.serviceId()).isEqualTo(UUID.fromString(scene.serviceId()));
        assertThat(response.range()).isEqualTo("24h");
        assertThat(response.from()).isEqualTo(FROM_24H);
        assertThat(response.to()).isEqualTo(TO_24H);

        var summary = response.summary();
        assertThat(summary.totalChecks()).isEqualTo(5);
        assertThat(summary.success()).isEqualTo(3);
        assertThat(summary.targetFailure()).isEqualTo(2);
        assertThat(summary.availability()).isEqualByComparingTo("60.00");
        assertThat(summary.successRate()).isEqualByComparingTo("60.00");
        // latência só dos 3 SUCCESS da Alpha (100ms); as falhas da Beta ficam fora
        assertThat(summary.avgLatencyMs()).isEqualByComparingTo("100.00");
        assertThat(summary.minLatencyMs()).isEqualTo(100L);
        assertThat(summary.maxLatencyMs()).isEqualTo(100L);

        // Gamma não teve check na janela → fora do breakdown
        List<ServiceMetricsBreakdown> breakdown = response.monitors();
        assertThat(breakdown)
                .extracting(ServiceMetricsBreakdown::monitorName)
                .containsExactly("Alpha", "Beta");
        assertThat(breakdown)
                .extracting(ServiceMetricsBreakdown::monitorId)
                .containsExactly(UUID.fromString(alpha), UUID.fromString(beta));
        assertThat(breakdown.get(0).totalChecks()).isEqualTo(3);
        assertThat(breakdown.get(1).totalChecks()).isEqualTo(2);
        assertThat(breakdown.get(1).availability()).isEqualByComparingTo("0.00");
        assertThat(breakdown.get(0).avgLatencyMs()).isEqualByComparingTo("100.00");
        assertThat(breakdown.get(0).minLatencyMs()).isEqualTo(100L);
        assertThat(breakdown.get(0).maxLatencyMs()).isEqualTo(100L);
        assertThat(breakdown.get(1).avgLatencyMs())
                .as("Beta só teve TARGET_FAILURE → sem latência de resposta")
                .isNull();
        assertThat(breakdown.get(1).minLatencyMs()).isNull();
        assertThat(breakdown.get(1).maxLatencyMs()).isNull();

        // a soma do breakdown fecha com o resumo, e a série também
        assertThat(breakdown.stream().mapToLong(ServiceMetricsBreakdown::totalChecks).sum())
                .isEqualTo(summary.totalChecks());
        assertThat(response.buckets()).hasSize(24);
        assertThat(response.buckets().stream().mapToLong(MetricsBucket::totalChecks).sum())
                .isEqualTo(summary.totalChecks());
    }

    @Test
    void emptyServiceKeepsGridAndBreakdownEmpty() throws Exception {
        Scene scene = newScene("mtr-svempty", "Mtr SvEmpty Corp");
        createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Sem Checks");

        ServiceMetricsResponse response = serviceMetricsOf(scene, null);

        assertThat(response.summary().totalChecks()).isZero();
        assertThat(response.summary().availability()).isNull();
        assertThat(response.buckets()).hasSize(24);
        assertThat(response.monitors()).isEmpty();
    }

    // --- volume -------------------------------------------------------------

    @Test
    void thousandsOfChecksStillProduceABoundedSeries() throws Exception {
        Scene scene = newScene("mtr-volume", "Mtr Volume Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Volume");

        Monitor monitor = monitorOf(scene.serviceId(), monitorId);
        List<MonitorCheck> rows = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            // distribuídos por toda a janela de 24h (34s de espaçamento)
            Instant startedAt = FROM_24H.plusSeconds(i * 34L);
            rows.add(new MonitorCheck(
                    monitor,
                    startedAt,
                    startedAt.plusMillis(100 + (i % 50)),
                    100 + (i % 50),
                    200,
                    CheckOutcome.SUCCESS,
                    null,
                    "volume check"));
        }
        monitorCheckRepository.saveAll(rows);

        MonitorMetricsResponse response = metricsOf(scene, monitorId, null);

        assertThat(response.buckets())
                .as("a série é BOUNDED pelo tamanho da faixa, nunca pelo volume")
                .hasSize(24);
        assertThat(response.summary().totalChecks()).isEqualTo(2500);
        assertThat(response.summary().success()).isEqualTo(2500);
        assertThat(response.summary().availability()).isEqualByComparingTo("100.00");
        assertThat(response.summary().successRate()).isEqualByComparingTo("100.00");
        // 2500 médias de 100..149 (50 ciclos inteiros) → 124.50 (todos SUCCESS)
        assertThat(response.summary().avgLatencyMs()).isEqualByComparingTo("124.50");
        assertThat(response.summary().minLatencyMs()).isEqualTo(100L);
        assertThat(response.summary().maxLatencyMs()).isEqualTo(149L);
        assertThat(response.buckets().stream().mapToLong(MetricsBucket::totalChecks).sum())
                .isEqualTo(2500);
    }
}
