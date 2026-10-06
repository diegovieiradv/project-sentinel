package com.sentinel.project.incident.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha da lista de incidentes ativos do painel (Prompt 16).
 *
 * <p>{@code associatedAlertsCount} é contado na PRÓPRIA query (subquery
 * correlacionada sobre {@code alerts}) — o painel no máximo 10 linhas, mas
 * uma consulta por incidente ainda seria N+1.</p>
 */
public interface ActiveIncidentRowProjection {

    UUID getIncidentId();

    UUID getServiceId();

    String getServiceName();

    String getTitle();

    String getSeverity();

    String getStatus();

    String getSource();

    Instant getStartedAt();

    long getAssociatedAlertsCount();
}
