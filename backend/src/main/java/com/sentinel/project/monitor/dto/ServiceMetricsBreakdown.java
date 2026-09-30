package com.sentinel.project.monitor.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma linha do breakdown por monitor de {@code GET
 * .../services/{serviceId}/metrics} (Prompt 11) — o agregado da janela de
 * UM monitor do serviço.
 *
 * <p><b>Mesmas fórmulas de {@link MetricsSummary}</b>, com os identificadores
 * que o cliente precisa para ligar a linha ao monitor. Os campos são
 * espelhados (e não aninhados) para que o JSON seja plano e a tabela de
 * breakdown possa ordenar/filtrar sem desembrulhar.</p>
 *
 * <p>Monitores sem nenhum check na janela NÃO aparecem no breakdown — a
 * lista é derivada das próprias linhas de histórico, então a soma das
 * linhas fecha exatamente com o {@code summary} do serviço.</p>
 */
public record ServiceMetricsBreakdown(
        UUID monitorId,
        String monitorName,
        long totalChecks,
        long success,
        long degraded,
        long targetFailure,
        long sentinelInternalFailure,
        BigDecimal availability,
        BigDecimal successRate,
        BigDecimal avgLatencyMs,
        Long minLatencyMs,
        Long maxLatencyMs) {
}
