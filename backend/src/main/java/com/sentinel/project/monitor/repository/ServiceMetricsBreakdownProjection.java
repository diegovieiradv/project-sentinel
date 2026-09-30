package com.sentinel.project.monitor.repository;

import java.util.UUID;

/**
 * Uma linha do breakdown por monitor do serviço (Prompt 11) — as mesmas
 * contagens e latência de {@link MetricsSummaryProjection} mais a
 * identidade do monitor dono das linhas.
 *
 * <p>A query agrupa por {@code monitor_id} dentro da janela: só aparecem
 * monitores que TIVERAM check no período, o que garante que a soma das
 * linhas fecha exatamente com o resumo do serviço (nenhuma linha "fantasma"
 * para o cliente reconciliar).</p>
 */
public interface ServiceMetricsBreakdownProjection extends MetricsSummaryProjection {

    UUID getMonitorId();

    String getMonitorName();
}
