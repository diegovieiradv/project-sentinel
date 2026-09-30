package com.sentinel.project.monitor.repository;

import java.math.BigDecimal;

/**
 * Contagens brutas de uma janela de métrica (Prompt 11) — a RESUMO lido do
 * banco, antes das fórmulas do service.
 *
 * <p>A query de resumo é uma agregação SEM {@code GROUP BY}: ela devolve
 * EXATAMENTE uma linha, inclusive para janela vazia (contagens 0 e média
 * {@code null}) — a API nunca decide entre "sem dados" e "erro".</p>
 *
 * <p>As quatro contagens fecham o domínio inteiro de
 * {@code CheckOutcome} (o CHECK da V8 só admite esses quatro valores), então
 * a soma delas é o {@code totalChecks}. Falhas internas do Sentinel entram
 * na contagem mas ficam fora dos denominadores de disponibilidade/sucesso —
 * a tradução dessa regra é do service, não do banco.</p>
 *
 * <p>A latência ({@code avg}/{@code min}/{@code max} de
 * {@code duration_millis}) chega JÁ filtrada pelo banco sobre
 * SUCCESS/DEGRADED — é a única regra de métrica aplicada na query; o
 * arredondamento da média é do service.</p>
 */
public interface MetricsSummaryProjection {

    long getSuccessCount();

    long getDegradedCount();

    long getTargetFailureCount();

    long getSentinelInternalFailureCount();

    /** Média de latência dos checks SUCCESS/DEGRADED ({@code null} sem nenhum). */
    BigDecimal getAvgDurationMillis();

    /** Menor latência (ms) entre SUCCESS/DEGRADED ({@code null} sem nenhum). */
    Long getMinDurationMillis();

    /** Maior latência (ms) entre SUCCESS/DEGRADED ({@code null} sem nenhum). */
    Long getMaxDurationMillis();
}
