package com.sentinel.project.monitor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.monitoring.Clock;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.monitor.dto.MetricsBucket;
import com.sentinel.project.monitor.dto.MetricsRange;
import com.sentinel.project.monitor.dto.MetricsSummary;
import com.sentinel.project.monitor.dto.MonitorMetricsResponse;
import com.sentinel.project.monitor.dto.ProjectWindowSummary;
import com.sentinel.project.monitor.dto.ServiceMetricsBreakdown;
import com.sentinel.project.monitor.dto.ServiceMetricsResponse;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.repository.MetricsBucketProjection;
import com.sentinel.project.monitor.repository.MetricsSummaryProjection;
import com.sentinel.project.monitor.repository.MonitorCheckRepository;
import com.sentinel.project.monitor.repository.MonitorRepository;
import com.sentinel.project.monitor.repository.ServiceMetricsBreakdownProjection;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Métricas históricas de monitor e de serviço (Prompt 11) — leitura pura
 * sobre {@code monitor_checks}, sem escrita e sem materialização.
 *
 * <p><b>Fonte única:</b> as quatro tabelas da hierarquia entram apenas para
 * PROVAR a cadeia Organization → Project → Service → Monitor (mesmo arranjo
 * do {@code MonitorService}); todo número vem de agregações no banco sobre
 * a janela pedida. Nada aqui escreve — inclusive {@code Service.status},
 * que continua sendo escritor exclusivamente pelo
 * {@code OperationalStateEvaluator} do Prompt 10.</p>
 *
 * <p><b>Janela meia-aberta em buckets completos:</b> {@code to} é o instante
 * atual truncado no tamanho do bucket e {@code from} é {@code to −
 * rangeSeconds}. O bucket em curso fica de fora, então todos os buckets da
 * resposta têm o MESMO tamanho e a série não muda de forma durante o
 * carregamento. A janela é {@code [from, to)}: um check que nasce exatamente
 * em {@code to} pertence ao período seguinte.</p>
 *
 * <p><b>Relógio injetável:</b> o recorte depende de "agora" e por isso usa o
 * MESMO {@link Clock} do motor de monitoramento ({@code SystemClock} em
 * produção, relógio controlado nos testes) — nunca {@code Instant.now()}
 * direto, que tornaria o teste da janela não-determinístico.</p>
 *
 * <p><b>Fórmula única — nunca média de percentuais nem média de médias
 * (Prompt 17 / PERF-12):</b> as agregações vêm do banco como CONTAGENS
 * brutas ({@code count(*) FILTER}, {@code avg/min/max FILTER} — ver
 * {@code MonitorCheckRepository.summaryByService}/{@code summaryByProject},
 * sempre UMA linha e sem {@code GROUP BY}) e as taxas são calculadas UMA vez
 * aqui, em {@link #toSummary}, sobre essas contagens somadas. A disponibilidade
 * de um projeto é a dos CHECKS somados do projeto — nunca a média das
 * disponibilidades dos serviços, que daria o mesmo peso a um serviço com 3
 * checks e outro com 3000. Idem a latência: o {@code avg} do banco varre as
 * linhas da janela, não as médias de buckets nem de serviços. Denominador
 * zero devolve {@code null} ("sem dado"), nunca 0 implícito. A mesma política
 * está escrita em {@code DashboardQueryService} e em {@code docs/dashboard.md}
 * — este é o ponto onde ela é IMPLEMENTADA.</p>
 *
 * <p><b>Ordenação da cadeia × range:</b> a cadeia do tenant roda PRIMEIRO
 * (404 genérico) e só depois o parâmetro {@code range} é validado (400) —
 * mesma ordem contrato dos prompts anteriores (recurso inexistente não é
 * diagnosticado por parâmetro).</p>
 *
 * <p><b>Escopo do serviço:</b> agregação de TODOS os checks dos monitores do
 * serviço, inclusive dos inativos — histórico não é apagado ao desligar um
 * monitor, e a janela de avaliação do Prompt 10 também considera todos os
 * monitores ativos sem "lembrança" além do histórico. Isto NÃO é uptime
 * temporal do serviço (isso não existe neste prompt).</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (mesmo padrão de MonitorService).
@org.springframework.stereotype.Service
public class MonitorMetricsService {

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public MonitorMetricsService(
            MonitorRepository monitorRepository,
            MonitorCheckRepository monitorCheckRepository,
            ServiceRepository serviceRepository,
            ProjectRepository projectRepository,
            Clock clock) {
        this.monitorRepository = monitorRepository;
        this.monitorCheckRepository = monitorCheckRepository;
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    // --- GET .../monitors/{monitorId}/metrics ------------------------------

    /**
     * Métricas de UM monitor: resumo da janela + a grade completa de buckets,
     * com a MESMA cadeia de leitura do histórico ({@code get}/{@code checks}):
     * projeto no tenant → serviço no projeto → monitor no serviço, 404
     * genérico em qualquer nível, pais inativos continuam legíveis.
     *
     * @param rangeKey valor cru do {@code range} ({@code null} → default 24h)
     */
    @Transactional(readOnly = true)
    public MonitorMetricsResponse monitorMetrics(
            UUID projectId, UUID serviceId, UUID monitorId, String rangeKey) {
        Service service = requireParents(projectId, serviceId);
        Monitor monitor = findMonitor(service, monitorId);
        MetricsRange range = MetricsRange.fromKey(rangeKey);
        Window window = Window.of(clock, range);

        List<MetricsBucket> buckets = monitorCheckRepository
                .bucketRowsByMonitor(
                        monitor.getId(),
                        window.from(),
                        window.to(),
                        window.firstBucketEpoch(),
                        window.lastBucketEpoch(),
                        window.bucketSeconds())
                .stream()
                .map(MonitorMetricsService::toBucket)
                .toList();

        MetricsSummary summary = toSummary(monitorCheckRepository.summaryByMonitor(
                monitor.getId(), window.from(), window.to()));

        return new MonitorMetricsResponse(
                monitor.getId(), range.key(), window.from(), window.to(),
                window.bucketSeconds(), summary, buckets);
    }

    // --- GET .../services/{serviceId}/metrics ------------------------------

    /**
     * Métricas de UM serviço: resumo da janela + grade de buckets do serviço
     * + breakdown por monitor (só monitores com check no período, ordenados
     * por nome). Cadeia de leitura idêntica ao {@code get} do serviço:
     * projeto no tenant → serviço no projeto, 404 genérico, leitura livre
     * para qualquer papel e pais inativos legíveis.
     *
     * @param rangeKey valor cru do {@code range} ({@code null} → default 24h)
     */
    @Transactional(readOnly = true)
    public ServiceMetricsResponse serviceMetrics(UUID projectId, UUID serviceId, String rangeKey) {
        Service service = requireParents(projectId, serviceId);
        MetricsRange range = MetricsRange.fromKey(rangeKey);
        Window window = Window.of(clock, range);

        List<MetricsBucket> buckets = monitorCheckRepository
                .bucketRowsByService(
                        service.getId(),
                        window.from(),
                        window.to(),
                        window.firstBucketEpoch(),
                        window.lastBucketEpoch(),
                        window.bucketSeconds())
                .stream()
                .map(MonitorMetricsService::toBucket)
                .toList();

        MetricsSummary summary = toSummary(monitorCheckRepository.summaryByService(
                service.getId(), window.from(), window.to()));

        List<ServiceMetricsBreakdown> breakdown = monitorCheckRepository
                .breakdownRowsByService(service.getId(), window.from(), window.to())
                .stream()
                .map(MonitorMetricsService::toBreakdown)
                .toList();

        return new ServiceMetricsResponse(
                service.getId(), range.key(), window.from(), window.to(),
                window.bucketSeconds(), summary, buckets, breakdown);
    }

    // --- resumo do PROJETO para o painel (Prompt 16) ------------------------

    /**
     * Janela alinhada + resumo de TODOS os checks do projeto — o recorte que
     * o painel operacional publica em {@code windowFrom}/{@code windowTo}.
     *
     * <p><b>Por que aqui:</b> a aritmética da janela ({@code to} truncado no
     * bucket e {@code from = to − range}) e as fórmulas do resumo moram no
     * {@link Window} privado e no {@link #toSummary}. O painel chama este
     * método para obter os dois com UMA fonte de verdade — reimplementar o
     * {@code floor} em outro serviço criaria um segundo recorte possível de
     * divergir do histórico.</p>
     *
     * <p>Sem cadeia de tenant aqui: quem prova Organization → Project é o
     * {@code DashboardQueryService}, como nos demais endpoints de leitura.</p>
     *
     * @param projectId projeto dono dos checks
     * @param range     faixa pedida ({@code null} já resolvido antes por
     *                  {@code MetricsRange.fromKey})
     */
    @Transactional(readOnly = true)
    public ProjectWindowSummary projectWindowSummary(UUID projectId, MetricsRange range) {
        Window window = Window.of(clock, range);
        MetricsSummary summary = toSummary(monitorCheckRepository.summaryByProject(
                projectId, window.from(), window.to()));
        return new ProjectWindowSummary(range.key(), window.from(), window.to(), summary);
    }

    // --- janela arbitrária para o Alert Engine (Prompt 13) ------------------

    /**
     * Resumo de uma janela QUALQUER de UM serviço — reutilização sem
     * duplicação das fórmulas do Prompt 11 pelo Alert Engine.
     *
     * <p><b>Por que público:</b> o engine precisa das MESMAS fórmulas
     * (availability/successRate/avgLatency com denominador de relevantes e
     * arredondamento HALF_UP) e reimplementá-las seria fonte dupla de
     * verdade. Aqui apenas chama
     * {@code summaryByService} + {@link #toSummary} — nada de novo é
     * calculado.</p>
     *
     * <p><b>Sem cadeia de tenant:</b> é chamada pelo motor interno (já dentro
     * do serviço resolvido), não pela API. Janela meia-aberta
     * {@code [from, to)} arbitrária — ao contrário da resposta HTTP, que se
     * alinha à grade de buckets, o engine recorta em
     * {@code agora − window.seconds}.</p>
     *
     * @param serviceId serviço dono
     * @param from      início da janela (incluso)
     * @param to        fim da janela (excluso)
     */
    @Transactional(readOnly = true)
    public MetricsSummary windowSummaryByService(UUID serviceId, Instant from, Instant to) {
        return toSummary(monitorCheckRepository.summaryByService(serviceId, from, to));
    }

    /**
     * Resumo de uma janela QUALQUER de UM monitor — análogo de
     * {@link #windowSummaryByService} para as regras com escopo de monitor.
     *
     * @param monitorId monitor dono do escopo
     * @param from      início da janela (incluso)
     * @param to        fim da janela (excluso)
     */
    @Transactional(readOnly = true)
    public MetricsSummary windowSummaryByMonitor(UUID monitorId, Instant from, Instant to) {
        return toSummary(monitorCheckRepository.summaryByMonitor(monitorId, from, to));
    }

    // --- cadeia Organization → Project → Service → Monitor ------------------
    /**
     * Espelho EXATO da cadeia de leitura do {@code MonitorService} (cujos
     * métodos são privados): projeto dentro do tenant → serviço dentro do
     * projeto → monitor dentro do serviço, 404 genérico em qualquer nível,
     * sem exigir pais ativos.
     */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(MonitorMetricsService::notFound);
    }

    private Service findService(Project project, UUID serviceId) {
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(MonitorMetricsService::notFound);
    }

    private Service requireParents(UUID projectId, UUID serviceId) {
        return findService(findProjectInTenant(projectId), serviceId);
    }

    private Monitor findMonitor(Service service, UUID monitorId) {
        return monitorRepository
                .findByServiceIdAndId(service.getId(), monitorId)
                .orElseThrow(MonitorMetricsService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    // --- janela -------------------------------------------------------------

    /**
     * Recorte de tempo de UMA resposta: janela meia-aberta {@code [from, to)}
     * alinhada à grade de buckets, com os dois epochs que a query de série
     * usa para gerar a grade completa.
     *
     * @param windowFrom          início da janela (epoch, incluso)
     * @param windowTo            fim da janela (epoch, excluso)
     * @param firstBucketEpoch    início do primeiro bucket completo (= windowFrom)
     * @param lastBucketEpoch     início do último bucket completo (= windowTo − bs)
     */
    private record Window(
            MetricsRange range,
            Instant from,
            Instant to,
            long firstBucketEpoch,
            long lastBucketEpoch) {

        long bucketSeconds() {
            return range.bucketSeconds();
        }

        /**
         * Recorte a partir do relógio injetado: {@code to = floor(agora / bs)
         * × bs} (fim do último bucket COMPLETO) e {@code from = to −
         * rangeSeconds}. {@code Math.floorDiv} espelha o {@code floor()} da
         * própria query — a grade nasce da MESMA aritmética nos dois lados.
         */
        static Window of(Clock clock, MetricsRange range) {
            long bucketSeconds = range.bucketSeconds();
            long nowEpoch = clock.instant().getEpochSecond();
            long toEpoch = Math.floorDiv(nowEpoch, bucketSeconds) * bucketSeconds;
            long fromEpoch = toEpoch - range.rangeSeconds();
            return new Window(
                    range,
                    Instant.ofEpochSecond(fromEpoch),
                    Instant.ofEpochSecond(toEpoch),
                    fromEpoch,
                    toEpoch - bucketSeconds);
        }
    }

    // --- tradução das linhas do banco ---------------------------------------

    /**
     * Contagens brutas → resumo com as fórmulas do contrato (fonte de
     * verdade no backend, arredondamento HALF_UP com 2 casas).
     *
     * <p>{@code relevantes} exclui falhas internas do Sentinel dos
     * denominadores; com zero relevante as taxas são {@code null} — a janela
     * vazia é "sem dado", não 0% nem 100%.</p>
     */
    private static MetricsSummary toSummary(MetricsSummaryProjection row) {
        long success = row.getSuccessCount();
        long degraded = row.getDegradedCount();
        long targetFailure = row.getTargetFailureCount();
        long internal = row.getSentinelInternalFailureCount();
        long relevant = success + degraded + targetFailure;
        return new MetricsSummary(
                success + degraded + targetFailure + internal,
                success,
                degraded,
                targetFailure,
                internal,
                ratio(success + degraded, relevant),
                ratio(success, relevant),
                round2(row.getAvgDurationMillis()),
                row.getMinDurationMillis(),
                row.getMaxDurationMillis());
    }

    /** Bucket da série: mesmas fórmulas do resumo, calculadas sobre a própria linha. */
    private static MetricsBucket toBucket(MetricsBucketProjection row) {
        long success = row.getSuccessCount();
        long degraded = row.getDegradedCount();
        long targetFailure = row.getTargetFailureCount();
        long internal = row.getSentinelInternalFailureCount();
        long relevant = success + degraded + targetFailure;
        return new MetricsBucket(
                Instant.ofEpochSecond(row.getBucketEpoch()),
                success + degraded + targetFailure + internal,
                success,
                degraded,
                targetFailure,
                internal,
                ratio(success + degraded, relevant),
                ratio(success, relevant),
                round2(row.getAvgDurationMillis()),
                row.getMinDurationMillis(),
                row.getMaxDurationMillis());
    }

    /** Linha do breakdown: identidade do monitor + as mesmas contagens/taxas. */
    private static ServiceMetricsBreakdown toBreakdown(
            ServiceMetricsBreakdownProjection row) {
        long success = row.getSuccessCount();
        long degraded = row.getDegradedCount();
        long targetFailure = row.getTargetFailureCount();
        long internal = row.getSentinelInternalFailureCount();
        long relevant = success + degraded + targetFailure;
        return new ServiceMetricsBreakdown(
                row.getMonitorId(),
                row.getMonitorName(),
                success + degraded + targetFailure + internal,
                success,
                degraded,
                targetFailure,
                internal,
                ratio(success + degraded, relevant),
                ratio(success, relevant),
                round2(row.getAvgDurationMillis()),
                row.getMinDurationMillis(),
                row.getMaxDurationMillis());
    }

    /**
     * Porcentagem com 2 casas e arredondamento HALF_UP; denominador zero →
     * {@code null} (nunca divisão por zero, nunca 0 implícito).
     */
    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /**
     * Latência média vem do banco com escala livre; o contrato publica 2
     * casas. Mín/máx chegam como {@code Long} inteiros e apenas propagam o
     * {@code null} do banco (sem SUCCESS/DEGRADED → tudo null, nunca 0).
     */
    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
