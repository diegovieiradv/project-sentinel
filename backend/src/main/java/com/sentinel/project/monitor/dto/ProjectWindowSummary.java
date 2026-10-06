package com.sentinel.project.monitor.dto;

import java.time.Instant;

/**
 * Janela + resumo do PROJETO para o painel operacional (Prompt 16) — o
 * recorte meia-aberta {@code [from, to)} alinhado à grade do Prompt 11 junto
 * com a agregação de todos os checks do projeto.
 *
 * <p><b>Por que existe:</b> o painel precisa da MESMA janela (e das MESMAS
 * fórmulas) do histórico — expor o par janela+resumo de uma vez mantém o
 * alinhamento de bucket em um único lugar ({@code Window} do
 * {@code MonitorMetricsService}) em vez de o painel refazer a aritmética de
 * {@code floor(agora / bucket) × bucket}.</p>
 *
 * <p>{@code summary} é o {@link MetricsSummary} canônico: mesmas fórmulas,
 * mesmo arredondamento HALF_UP e os MESMOS {@code null} de janela vazia.</p>
 */
public record ProjectWindowSummary(String rangeKey, Instant from, Instant to, MetricsSummary summary) {
}
