package com.sentinel.project.monitor.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Um bucket da série temporal de métricas (Prompt 11) — agregado de todos
 * os checks cujo {@code startedAt} caiu naquele intervalo de tempo.
 *
 * <p><b>Grade completa, sempre:</b> a resposta traz TODOS os buckets da
 * faixa mesmo sem check no período (contagens zeradas e taxas
 * {@code null}) — o eixo do gráfico é estável e um buraco no histórico é
 * distinguível de uma resposta incompleta.</p>
 *
 * <p>{@code bucketStart} é o início do intervalo, truncado no tamanho do
 * bucket, e o intervalo é meia-aberto
 * {@code [bucketStart, bucketStart + bucketSeconds)} — o mesmo enquadramento
 * que o resumo aplica sobre a janela inteira.</p>
 *
 * <p>Fórmulas e arredondamento são os de {@link MetricsSummary}; cada
 * bucket calcula as próprias taxas a partir das próprias contagens (a soma
 * de buckets NÃO é igual à taxa da janela — é a média ponderada que
 * produziria a taxa global, e por isso o resumo é calculado à parte).</p>
 */
public record MetricsBucket(
        Instant bucketStart,
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
