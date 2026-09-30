package com.sentinel.project.monitor.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta de {@code GET .../monitors/{monitorId}/metrics} (Prompt 11) —
 * métricas de UM monitor sobre a janela pedida.
 *
 * <p><b>Só agregado, nunca linha:</b> a resposta não carrega checagens
 * individuais (a lista crua continua sendo o {@code GET .../checks}) nem
 * configuração do monitor. {@code from}/{@code to} são a janela EXATA usada
 * no cálculo — meia-aberta {@code [from, to)} — para o cliente conseguir
 * reproduzir o enquadramento sem adivinhar timezone.</p>
 *
 * <p>{@code buckets} tem sempre {@code bucketCount} elementos, em ordem
 * crescente de {@code bucketStart}.</p>
 */
public record MonitorMetricsResponse(
        UUID monitorId,
        String range,
        Instant from,
        Instant to,
        long bucketSeconds,
        MetricsSummary summary,
        List<MetricsBucket> buckets) {
}
