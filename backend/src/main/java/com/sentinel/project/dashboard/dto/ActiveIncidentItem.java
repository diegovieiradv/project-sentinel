package com.sentinel.project.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.incident.model.IncidentStatus;

/**
 * Uma linha da lista de incidentes ativos do painel.
 *
 * <p>{@code associatedAlertsCount} é contado na PRÓPRIA linha da listagem
 * (subquery correlacionada sobre {@code alerts}, no máximo 10 linhas) — uma
 * consulta por incidente viraria N+1 na API.</p>
 */
public record ActiveIncidentItem(
        UUID incidentId,
        UUID serviceId,
        String serviceName,
        String title,
        AlertSeverity severity,
        IncidentStatus status,
        IncidentSource source,
        Instant startedAt,
        long associatedAlertsCount) {
}
