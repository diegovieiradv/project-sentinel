package com.sentinel.project.incident.web.dto;

import com.sentinel.project.incident.model.IncidentStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/incidents/{incidentId}/status.
 *
 * <p>{@code @NotNull} de propósito: body ausente, {@code {}} ou
 * {@code {"status": null}} respondem {@code 400} — nunca um
 * {@code status} implícito. O servidor não "adivinha" para onde o incidente
 * deve ir.</p>
 *
 * <p><b>Não é idempotente</b> (ao contrário de {@code ProjectStatusRequest}):
 * autotransição {@code X → X} responde {@code 409 INCIDENT_STATUS_TRANSITION},
 * porque existe uma rota própria de estado e um no-op silencioso esconderia
 * um cliente desatualizado (critério 17 do prompt).</p>
 */
public record IncidentStatusRequest(@NotNull IncidentStatus status) {
}
