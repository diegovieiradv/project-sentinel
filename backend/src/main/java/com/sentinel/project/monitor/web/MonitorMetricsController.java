package com.sentinel.project.monitor.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.monitor.dto.MonitorMetricsResponse;
import com.sentinel.project.monitor.service.MonitorMetricsService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de métricas do monitor (Prompt 11):
 * {@code GET .../services/{serviceId}/monitors/{monitorId}/metrics?range=24h}.
 *
 * <p>Rota MESMA base do {@link MonitorController} e mesmo arranjo dos
 * endpoints de leitura: handler {@code @TenantScoped} (membership validada
 * pelo {@link com.sentinel.organization.web.TenantInterceptor} ANTES do
 * handler), IDs parseados à mão (UUID malformado → o mesmo 404 genérico da
 * API, nunca 400) e nenhum método de escrita — um POST/PATCH na rota cai no
 * contrato global de 405.</p>
 *
 * <p>{@code range} é o ÚNICO parâmetro e é opcional (ausente → default do
 * servidor, {@code 24h}); valor desconhecido vira 400 dentro do service,
 * DEPOIS de a cadeia do tenant ser provada (404 antes de 400).</p>
 */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors")
public class MonitorMetricsController {

    private final MonitorMetricsService monitorMetricsService;

    public MonitorMetricsController(MonitorMetricsService monitorMetricsService) {
        this.monitorMetricsService = monitorMetricsService;
    }

    /**
     * GET /{monitorId}/metrics → 200 resumo + série de buckets (qualquer
     * papel; pais inativos continuam legíveis).
     *
     * @param range faixa opcional: {@code 1h | 6h | 24h | 7d | 30d}
     */
    @GetMapping("/{monitorId}/metrics")
    @TenantScoped
    public MonitorMetricsResponse metrics(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String monitorId,
            @RequestParam(name = "range", required = false) String range) {
        return monitorMetricsService.monitorMetrics(
                parseId(projectId), parseId(serviceId), parseId(monitorId), range);
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
