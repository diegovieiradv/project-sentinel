package com.sentinel.project.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.monitor.dto.ServiceMetricsResponse;
import com.sentinel.project.monitor.service.MonitorMetricsService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de métricas do serviço (Prompt 11):
 * {@code GET .../projects/{projectId}/services/{serviceId}/metrics?range=24h}.
 *
 * <p>Fica ao lado do {@link ServiceController} (mesma base de rota) com o
 * MESMO arranjo dos endpoints de leitura: handler {@code @TenantScoped},
 * IDs parseados à mão (UUID malformado → 404 genérico) e somente GET.</p>
 *
 * <p>As métricas agregam os checks de TODOS os monitores do serviço — a
 * resposta traz resumo, série temporal e breakdown por monitor. O estado
 * operacional ({@code Service.status}) continua vindo do endpoint de
 * detalhe do serviço, escrito pelo avaliador do Prompt 10.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/services")
public class ServiceMetricsController {

    private final MonitorMetricsService monitorMetricsService;

    public ServiceMetricsController(MonitorMetricsService monitorMetricsService) {
        this.monitorMetricsService = monitorMetricsService;
    }

    /**
     * GET /{serviceId}/metrics → 200 resumo + série de buckets + breakdown
     * por monitor (qualquer papel; pais inativos continuam legíveis).
     *
     * @param range faixa opcional: {@code 1h | 6h | 24h | 7d | 30d}
     */
    @GetMapping("/{serviceId}/metrics")
    @TenantScoped
    public ServiceMetricsResponse metrics(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestParam(name = "range", required = false) String range) {
        return monitorMetricsService.serviceMetrics(parseId(projectId), parseId(serviceId), range);
    }

    /** ID malformado → o MESMO 404 genérico do resto da API (anti-enumeração). */
    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
        }
    }
}
