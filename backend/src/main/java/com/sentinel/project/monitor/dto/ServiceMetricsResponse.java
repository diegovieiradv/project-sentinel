package com.sentinel.project.monitor.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta de {@code GET .../services/{serviceId}/metrics} (Prompt 11) —
 * métricas de UM serviço sobre a janela pedida.
 *
 * <p><b>Serviço = união dos checks de TODOS os seus monitores</b> (ativos e
 * inativos) na janela: a agregação é sobre linhas de histórico, não sobre o
 * estado atual do catálogo. É a mesma leitura do avaliador operacional do
 * Prompt 10, só que sobre a janela toda em vez de 3 checks por monitor.</p>
 *
 * <p>Três camadas, todas sobre a MESMA janela:</p>
 * <ul>
 *   <li>{@code summary} — o serviço inteiro;</li>
 *   <li>{@code buckets} — a série temporal do serviço (sempre
 *       {@code bucketCount} elementos);</li>
 *   <li>{@code monitors} — o breakdown por monitor, ordenado por nome.</li>
 * </ul>
 *
 * <p>{@code Service.status} (o estado operacional do Prompt 10) NÃO é
 * calculado nem exposto por aqui: este endpoint é leitura de histórico, o
 * eixo de estado continua sendo o do avaliador.</p>
 */
public record ServiceMetricsResponse(
        UUID serviceId,
        String range,
        Instant from,
        Instant to,
        long bucketSeconds,
        MetricsSummary summary,
        List<MetricsBucket> buckets,
        List<ServiceMetricsBreakdown> monitors) {
}
