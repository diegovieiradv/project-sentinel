package com.sentinel.project.dashboard.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.dashboard.dto.ActiveAlertItem;
import com.sentinel.project.dashboard.dto.ActiveIncidentItem;
import com.sentinel.project.dashboard.dto.ActivityItem;
import com.sentinel.project.dashboard.dto.ActivityResourceType;
import com.sentinel.project.dashboard.dto.ActivityType;
import com.sentinel.project.dashboard.dto.AlertsSummary;
import com.sentinel.project.dashboard.dto.AvailabilitySummary;
import com.sentinel.project.dashboard.dto.DashboardResponse;
import com.sentinel.project.dashboard.dto.HealthSummary;
import com.sentinel.project.dashboard.dto.IncidentsSummary;
import com.sentinel.project.dashboard.dto.ServiceAttentionItem;
import com.sentinel.project.dashboard.repository.ActivityRowProjection;
import com.sentinel.project.dashboard.repository.DashboardActivityRepository;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.incident.model.IncidentStatus;
import com.sentinel.project.incident.repository.IncidentRepository;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.dto.MetricsRange;
import com.sentinel.project.monitor.dto.MetricsSummary;
import com.sentinel.project.monitor.dto.ProjectWindowSummary;
import com.sentinel.project.monitor.service.MonitorMetricsService;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Painel operacional do projeto (Prompt 16) — leitura pura que COMPOE dados
 * já existentes em uma única resposta, sem escrita, sem job e sem cache.
 *
 * <p><b>Composição, não novo domínio:</b> cada seção reaproveita a fonte de
 * verdade do seu domínio — saúde de {@code Service.status} (Prompt 10),
 * disponibilidade das fórmulas do Prompt 11 (via
 * {@link MonitorMetricsService#projectWindowSummary}), alertas e incidentes
 * dos Prompts 13–14 e atividade da timeline do Prompt 14. Nada aqui cria
 * alerta, muda status, abre incidente ou escreve em qualquer tabela.</p>
 *
 * <p><b>Anti-N+1:</b> nove round-trips independentes do número de serviços —
 * agregações com {@code GROUP BY}, listas com {@code LIMIT} no banco e a
 * atividade como UNIÃO única. Nenhum loop dispara consulta por serviço,
 * alerta ou incidente.</p>
 *
 * <p><b>Ordenação de acesso:</b> a cadeia Organization → Project roda
 * PRIMEIRO (404 genérico anti-enumeração, mesmo lookup de todos os demais
 * endpoints) e só então o {@code range} é validado (400) — recurso
 * inexistente nunca é diagnosticado por parâmetro. A membership é provada
 * pelo {@code TenantInterceptor}/{@code @TenantScoped}: leitura para
 * qualquer papel (ADMIN/OPERATOR/VIEWER), como nas demais rotas GET.</p>
 *
 * <p><b>Estado vazio é 200:</b> projeto sem serviço responde com contagens
 * zeradas, listas vazias e {@code availabilityPercent = null}.</p>
 */
// Arranjo do conflito de nomes: o estereótipo do Spring vem por FQN abaixo
// (mesmo padrão de MonitorMetricsService).
@org.springframework.stereotype.Service
@Transactional(readOnly = true)
public class DashboardQueryService {

    /** Recortes de exibição do contrato — as contagens vêm das agregações. */
    private static final int MAX_ACTIVE_ALERTS = 10;
    private static final int MAX_ACTIVE_INCIDENTS = 10;
    private static final int MAX_ATTENTION_SERVICES = 20;
    private static final int MAX_ACTIVITY_ITEMS = 20;

    private final ProjectRepository projectRepository;
    private final ServiceRepository serviceRepository;
    private final AlertRepository alertRepository;
    private final IncidentRepository incidentRepository;
    private final DashboardActivityRepository activityRepository;
    private final MonitorMetricsService monitorMetricsService;

    public DashboardQueryService(
            ProjectRepository projectRepository,
            ServiceRepository serviceRepository,
            AlertRepository alertRepository,
            IncidentRepository incidentRepository,
            DashboardActivityRepository activityRepository,
            MonitorMetricsService monitorMetricsService) {
        this.projectRepository = projectRepository;
        this.serviceRepository = serviceRepository;
        this.alertRepository = alertRepository;
        this.incidentRepository = incidentRepository;
        this.activityRepository = activityRepository;
        this.monitorMetricsService = monitorMetricsService;
    }

    /**
     * {@code GET .../projects/{projectId}/dashboard?range=24h} — resposta
     * única do painel.
     *
     * @param projectId id do projeto (já parseado; malformado vira 404 no
     *                  controller)
     * @param rangeKey  valor cru do {@code range} ({@code null} → 24h)
     */
    public DashboardResponse dashboard(UUID projectId, String rangeKey) {
        Project project = requireProject(projectId);
        MetricsRange range = MetricsRange.fromKey(rangeKey);
        ProjectWindowSummary window =
                monitorMetricsService.projectWindowSummary(project.getId(), range);

        return new DashboardResponse(
                project.getId(),
                window.rangeKey(),
                window.from(),
                window.to(),
                health(project.getId()),
                availability(window.summary()),
                alerts(project.getId()),
                incidents(project.getId()),
                needingAttention(project.getId()),
                recentActivity(project.getId(), window));
    }

    // --- saúde -------------------------------------------------------------

    /**
     * Serviços por status em UMA consulta; a soma dos quatro estados fecha
     * com {@code totalServices} (vocabulário fechado do CHECK da V6).
     */
    private HealthSummary health(UUID projectId) {
        Map<ServiceStatus, Long> counts = new HashMap<>();
        long total = 0;
        for (var row : serviceRepository.countStatusByProject(projectId)) {
            long value = row.getTotal();
            counts.put(ServiceStatus.valueOf(row.getStatus()), value);
            total += value;
        }
        return new HealthSummary(
                total,
                counts.getOrDefault(ServiceStatus.OPERATIONAL, 0L),
                counts.getOrDefault(ServiceStatus.DEGRADED, 0L),
                counts.getOrDefault(ServiceStatus.UNAVAILABLE, 0L),
                counts.getOrDefault(ServiceStatus.UNKNOWN, 0L));
    }

    // --- disponibilidade ----------------------------------------------------

    /**
     * Disponibilidade global da janela: as fórmulas do Prompt 11 aplicadas a
     * TODOS os checks do projeto (numeradores/denominadores já somados no
     * banco — nunca média de médias por serviço).
     *
     * <p>{@code relevantChecks} é o denominador real
     * ({@code success + degraded + targetFailure}); janela sem dado mantém
     * os {@code null} canônicos.</p>
     */
    private AvailabilitySummary availability(MetricsSummary summary) {
        return new AvailabilitySummary(
                summary.availability(),
                summary.totalChecks(),
                summary.success() + summary.degraded() + summary.targetFailure(),
                summary.successRate(),
                summary.avgLatencyMs(),
                summary.minLatencyMs(),
                summary.maxLatencyMs());
    }

    // --- alertas ------------------------------------------------------------

    /** Contagens por severidade (GROUP BY) + as 10 primeiras linhas. */
    private AlertsSummary alerts(UUID projectId) {
        long sev1 = 0;
        long sev2 = 0;
        long sev3 = 0;
        long sev4 = 0;
        for (var row : alertRepository.countFiringBySeverityForProject(projectId)) {
            long value = row.getTotal();
            switch (AlertSeverity.valueOf(row.getSeverity())) {
                case SEV1 -> sev1 += value;
                case SEV2 -> sev2 += value;
                case SEV3 -> sev3 += value;
                case SEV4 -> sev4 += value;
            }
        }
        List<ActiveAlertItem> top = alertRepository
                .findTopFiringForProject(projectId, MAX_ACTIVE_ALERTS)
                .stream()
                .map(row -> new ActiveAlertItem(
                        row.getAlertId(),
                        row.getServiceId(),
                        row.getServiceName(),
                        row.getRuleId(),
                        row.getRuleName(),
                        AlertSeverity.valueOf(row.getSeverity()),
                        row.getFiredAt(),
                        row.getIncidentId()))
                .toList();
        return new AlertsSummary(sev1 + sev2 + sev3 + sev4, sev1, sev2, sev3, sev4, top);
    }

    // --- incidentes ---------------------------------------------------------

    /** Contagens ativas por status (GROUP BY) + as 10 primeiras linhas. */
    private IncidentsSummary incidents(UUID projectId) {
        long open = 0;
        long investigating = 0;
        long identified = 0;
        for (var row : incidentRepository.countActiveByStatusForProject(projectId)) {
            long value = row.getTotal();
            switch (IncidentStatus.valueOf(row.getStatus())) {
                case OPEN -> open += value;
                case INVESTIGATING -> investigating += value;
                case IDENTIFIED -> identified += value;
                case RESOLVED -> {
                    // filtrado pela query (status <> RESOLVED) — nunca chega
                }
            }
        }
        List<ActiveIncidentItem> top = incidentRepository
                .findTopActiveForProject(projectId, MAX_ACTIVE_INCIDENTS)
                .stream()
                .map(row -> new ActiveIncidentItem(
                        row.getIncidentId(),
                        row.getServiceId(),
                        row.getServiceName(),
                        row.getTitle(),
                        AlertSeverity.valueOf(row.getSeverity()),
                        IncidentStatus.valueOf(row.getStatus()),
                        IncidentSource.valueOf(row.getSource()),
                        row.getStartedAt(),
                        row.getAssociatedAlertsCount()))
                .toList();
        return new IncidentsSummary(open + investigating + identified, open, investigating, identified, top);
    }

    // --- serviços pedindo atenção -------------------------------------------

    /** Filtro + ordenação + limite resolvidos no banco (uma consulta). */
    private List<ServiceAttentionItem> needingAttention(UUID projectId) {
        return serviceRepository
                .findServicesNeedingAttention(projectId, MAX_ATTENTION_SERVICES)
                .stream()
                .map(row -> new ServiceAttentionItem(
                        row.getServiceId(),
                        row.getServiceName(),
                        ServiceStatus.valueOf(row.getStatus()),
                        mostSevere(
                                row.getHighestAlertSeverity(),
                                row.getHighestIncidentSeverity()),
                        Boolean.TRUE.equals(row.getHasActiveIncident()),
                        row.getStatusChangedAt()))
                .toList();
    }

    /**
     * Maior severidade entre alerta FIRING e incidente ativo (SEV1 &gt; SEV4);
     * {@code null} só quando nenhum dos dois existe — serviço UNAVAILABLE
     * sem alerta/incidente não tem severidade a reportar.
     */
    private static AlertSeverity mostSevere(String alertSeverity, String incidentSeverity) {
        AlertSeverity fromAlert =
                alertSeverity == null ? null : AlertSeverity.valueOf(alertSeverity);
        AlertSeverity fromIncident =
                incidentSeverity == null ? null : AlertSeverity.valueOf(incidentSeverity);
        if (fromAlert == null) {
            return fromIncident;
        }
        if (fromIncident == null) {
            return fromAlert;
        }
        return fromAlert.ordinal() <= fromIncident.ordinal() ? fromAlert : fromIncident;
    }

    // --- atividade recente --------------------------------------------------

    /**
     * UNIÃO das três fontes na janela, mais recente primeiro, limitada a 20 —
     * traduz os {@code String} nativos para os enums do contrato uma única
     * vez por linha.
     */
    private List<ActivityItem> recentActivity(UUID projectId, ProjectWindowSummary window) {
        List<ActivityRowProjection> rows = activityRepository.findRecentActivity(
                projectId, window.from(), window.to(), MAX_ACTIVITY_ITEMS);
        return rows.stream()
                .map(row -> new ActivityItem(
                        ActivityType.valueOf(row.getType()),
                        row.getTimestamp(),
                        row.getServiceId(),
                        row.getServiceName(),
                        row.getSeverity() == null
                                ? null
                                : AlertSeverity.valueOf(row.getSeverity()),
                        row.getTitle(),
                        row.getResourceId(),
                        ActivityResourceType.valueOf(row.getResourceType())))
                .toList();
    }

    // --- cadeia Organization → Project --------------------------------------

    /** Lookup canônico (mesmo do MonitorMetricsService): 404 genérico. */
    private Project requireProject(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(DashboardQueryService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }
}
