package com.sentinel.project.incident.model;

/**
 * Tipos da timeline append-only de um incidente (Prompt 14) — o vocabulary
 * fechado do {@code incident_events.type} (CHECK da migration V11).
 *
 * <p><b>Somente eventos de DOMÍNIO</b>, nunca "notification sent",
 * "escalated" ou "assigned": notificação, escalation e assignment não
 * existem neste prompt. Os sete tipos são exatamente os que uma mutação da
 * API pode produzir:</p>
 *
 * <ul>
 *   <li>{@link #CREATED} — nasce junto com o incidente (uma linha, na MESMA
 *       transação do INSERT);</li>
 *   <li>{@link #STATUS_CHANGED} / {@link #SEVERITY_CHANGED} /
 *       {@link #TITLE_CHANGED} / {@link #DESCRIPTION_CHANGED} — um evento
 *       POR campo efetivamente alterado (no-op não gera linha);</li>
 *   <li>{@link #ALERT_LINKED} / {@link #ALERT_UNLINKED} — associação
 *       manual com alertas do MESMO serviço.</li>
 * </ul>
 *
 * <p>Ordem de emissão de um PATCH com vários campos:
 * {@code title → description → severity} (a timeline espelha a ordem de
 * avaliação do serviço, e o status tem rota própria).</p>
 */
public enum IncidentEventType {

    CREATED,
    STATUS_CHANGED,
    SEVERITY_CHANGED,
    TITLE_CHANGED,
    DESCRIPTION_CHANGED,
    ALERT_LINKED,
    ALERT_UNLINKED
}
