package com.sentinel.project.monitor.dto;

import java.math.BigDecimal;

/**
 * Agregado de uma janela de métrica (Prompt 11) — o mesmo formato para o
 * monitor inteiro, para o serviço inteiro e para cada linha do breakdown
 * por monitor.
 *
 * <p><b>Contagens brutas + taxas derivadas.</b> As contagens são canônicas
 * (somam exatamente {@code totalChecks}) e as taxas são calculadas no
 * servidor a partir delas — o cliente nunca reconstrói porcentagem.</p>
 *
 * <p><b>Fórmulas (backend é a fonte de verdade, arredondamento HALF_UP com
 * 2 casas):</b></p>
 * <ul>
 *   <li>{@code relevantes = success + degraded + targetFailure};</li>
 *   <li>{@code availability = relevantes == 0 ? null :
 *       (success + degraded) × 100 / relevantes};</li>
 *   <li>{@code successRate = relevantes == 0 ? null :
 *       success × 100 / relevantes};</li>
 *   <li>{@code totalChecks = success + degraded + targetFailure
 *       + sentinelInternalFailure}.</li>
 * </ul>
 *
 * <p><b>{@code SENTINEL_INTERNAL_FAILURE} fica FORA dos denominadores:</b>
 * falha do próprio Sentinel não é evidência sobre o alvo (mesma regra da
 * janela de avaliação do Prompt 10). Ela conta em {@code totalChecks}
 * (aconteceu) mas nunca em {@code availability} nem em {@code successRate}.</p>
 *
 * <p><b>Zero relevante → {@code null}, nunca 0.</b> Uma janela sem nenhum
 * check (ou só falhas internas) não tem disponibilidade a reportar: JSON
 * {@code null} — não {@code 0.00}, não {@code 500.00}, não NaN.</p>
 *
 * <p><b>Latência só de SUCCESS/DEGRADED:</b> {@code avgLatencyMs},
 * {@code minLatencyMs} e {@code maxLatencyMs} medem o tempo de resposta do
 * check APENAS nos outcomes SUCCESS ou DEGRADED_SIGNAL — TARGET_FAILURE e
 * SENTINEL_INTERNAL_FAILURE ficam de fora (falha não é latência de
 * resposta). Sem nenhum SUCCESS/DEGRADED na janela, os três são
 * {@code null} — nunca 0. A média sai do banco com escala livre e o
 * contrato publica 2 casas (HALF_UP).</p>
 */
public record MetricsSummary(
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
