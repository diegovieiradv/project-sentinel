package com.sentinel.project.alert.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha da lista de alertas FIRING do painel (Prompt 16) — a resolução
 * (projeto, ordenação por severidade/tempo e limite) é da própria query.
 *
 * <p>Serviço e regra vêm no JOIN: a UI monta a linha sem N+1 de lookup.
 * {@code incidentId} é o vínculo opcional do Prompt 14/15 ({@code null}
 * quando não há incidente associado).</p>
 */
public interface ActiveAlertRowProjection {

    UUID getAlertId();

    UUID getServiceId();

    String getServiceName();

    UUID getRuleId();

    String getRuleName();

    String getSeverity();

    Instant getFiredAt();

    UUID getIncidentId();
}
