package com.sentinel.alerting;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.monitor.dto.MetricsSummary;

/**
 * Fonte de métricas de uma janela QUALQUER para o Alert Engine (Prompt 13) —
 * a fronteira que permite testar os avaliadores de regra sem banco, sem
 * relógio e sem Spring.
 *
 * <p><b>Por que uma interface:</b> os avaliadores precisam das fórmulas do
 * Prompt 11 (availability, successRate, avgLatency) mas não devem conhecer
 * {@code MonitorMetricsService}, tenant nem JPA. Em produção a implementação
 * é {@link MonitorWindowMetrics}; nos testes de unidade é um fake de uma
 * linha que devolve um {@link MetricsSummary} preparado — mesmo arranjo do
 * {@code llm-as-dependency} (dependência injetada, nunca chamada direta).</p>
 *
 * <p><b>Janela meia-aberta {@code [from, to)},</b> arbitrária (ao contrário
 * da grade de buckets da API): o engine recorta em
 * {@code agora − window.seconds}.</p>
 */
public interface WindowMetrics {

    /** Resumo da janela de UM serviço (regras Service-level). */
    MetricsSummary serviceWindow(UUID serviceId, Instant from, Instant to);

    /** Resumo da janela de UM monitor (regras Monitor-level). */
    MetricsSummary monitorWindow(UUID monitorId, Instant from, Instant to);
}
