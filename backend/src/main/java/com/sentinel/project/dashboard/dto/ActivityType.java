package com.sentinel.project.dashboard.dto;

/**
 * Tipos do feed de atividade recente do painel (Prompt 16) — vocabulário
 * fechado que traduz as três fontes (alertas, incidentes e a timeline do
 * Prompt 14) para um único eixo de tempo.
 *
 * <p>Os nomes são o contrato da API: o frontend faz rótulo/ícone por valor
 * e nunca infere tipo por string solta.</p>
 */
public enum ActivityType {

    /** Alerta começou a valer ({@code alerts.started_at}). */
    ALERT_FIRED,

    /** Alerta encerrado ({@code alerts.resolved_at}). */
    ALERT_RESOLVED,

    /** Incidente aberto ({@code incidents.started_at}). */
    INCIDENT_CREATED,

    /** Mudança de status na timeline ({@code STATUS_CHANGED}). */
    INCIDENT_STATUS_CHANGED,

    /** Alerta vinculado ao incidente ({@code ALERT_LINKED}). */
    INCIDENT_LINKED,

    /** Incidente encerrado ({@code incidents.resolved_at}). */
    INCIDENT_RESOLVED
}
