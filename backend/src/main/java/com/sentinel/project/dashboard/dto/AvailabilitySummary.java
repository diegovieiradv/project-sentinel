package com.sentinel.project.dashboard.dto;

import java.math.BigDecimal;

/**
 * Disponibilidade do projeto na janela — as MESMAS fórmulas do Prompt 11
 * aplicadas a todos os checks dos monitores de todos os serviços do projeto.
 *
 * <p><b>Global, nunca média de médias:</b> a agregação soma os numeradores
 * e denominadores do projeto inteiro e só então calcula as taxas
 * (arredondamento HALF_UP com 2 casas) — um serviço com 2 checks a 100% não
 * puxa a média de um serviço com 1000 checks a 50%.</p>
 *
 * <p><b>{@code SENTINEL_INTERNAL_FAILURE} fica fora dos denominadores</b>
 * (falha do Sentinel não é evidência sobre o alvo), mas conta em
 * {@code totalChecks}. {@code relevantChecks} é exatamente o denominador
 * usado: {@code success + degraded + targetFailure}.</p>
 *
 * <p><b>Janela sem dado → {@code null}, nunca 0:</b>
 * {@code availabilityPercent} e {@code successRatePercent} são
 * {@code null} quando não há nenhum check relevante, e a latência também
 * ({@code null} sem SUCCESS/DEGRADED) — a UI renderiza "No data".</p>
 */
public record AvailabilitySummary(
        BigDecimal availabilityPercent,
        long totalChecks,
        long relevantChecks,
        BigDecimal successRatePercent,
        BigDecimal avgLatencyMs,
        Long minLatencyMs,
        Long maxLatencyMs) {
}
