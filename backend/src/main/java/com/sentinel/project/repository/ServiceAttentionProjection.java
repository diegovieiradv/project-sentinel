package com.sentinel.project.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha de "serviço pedindo atenção" (Prompt 16) — a resolução inteira
 * (filtro, ordenação e limite) acontece NO banco.
 *
 * <p>As duas severidades vêm das subqueries correlacionadas (alerta FIRING
 * mais grave e incidente ativo mais grave, cada uma com {@code LIMIT 1} na
 * própria ordenação): o service só escolhe a maior das duas e traduz
 * {@code String → AlertSeverity}. N select por serviço, N+1 zero.</p>
 *
 * <p>{@code status} e as severidades são {@code String} pela mesma razão das
 * demais projeções nativas; {@code hasActiveIncident} já vem pronto do
 * {@code EXISTS} da query.</p>
 */
public interface ServiceAttentionProjection {

    UUID getServiceId();

    String getServiceName();

    String getStatus();

    Instant getStatusChangedAt();

    /** Severidade do alerta FIRING mais grave do serviço ou {@code null}. */
    String getHighestAlertSeverity();

    /** Severidade do incidente ativo mais grave do serviço ou {@code null}. */
    String getHighestIncidentSeverity();

    Boolean getHasActiveIncident();
}
