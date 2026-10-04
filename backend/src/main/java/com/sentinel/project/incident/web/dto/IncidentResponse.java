package com.sentinel.project.incident.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentStatus;

/**
 * Contrato de saída de UM incidente (Prompt 14) — entidade JPA nunca exposta
 * cru.
 *
 * <p><b>Identidade + escopo + ciclo de vida:</b> {@code id},
 * {@code organizationId}/{@code projectId}/{@code serviceId} (cadeia inteira,
 * mesmo arranjo do {@code AlertResponse}), o conteúdo editável
 * ({@code title}/{@code description}/{@code severity}), o estado
 * ({@code status}, {@code startedAt}, {@code resolvedAt}), o audit de
 * primeira linha ({@code createdByUserId}) e {@code createdAt}/
 * {@code updatedAt}/{@code version}.</p>
 *
 * <p><b>{@code createdByUserId} é SÓ o UUID</b> — a correção da revisão: nenhum
 * lookup de {@code User} e nenhum e-mail em nenhuma resposta deste recurso.
 * O objeto {@code User} pode ter sido removido (o histórico precisa sobreviver)
 * e expor e-mail aqui abriria uma superfície nova de vazamento que a API de
 * membros é cuidadosamente restringida a evitar.</p>
 *
 * <p><b>{@code version} exposto de propósito:</b> é o escudo de concorrência
 * do {@code @Version} — a UI pode exibir "atualizado por outra pessoa" quando
 * um PATCH devolve {@code 409}.</p>
 *
 * <p><b>{@code role}</b> segue vindo no payload como nos demais contratos do
 * projeto, para a UI decidir o que exibir.</p>
 */
public record IncidentResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        String title,
        String description,
        AlertSeverity severity,
        IncidentStatus status,
        Instant startedAt,
        Instant resolvedAt,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        Role role) {

    public static IncidentResponse from(Incident incident, Role role) {
        return new IncidentResponse(
                incident.getId(),
                incident.getService().getProject().getOrganization().getId(),
                incident.getService().getProject().getId(),
                incident.getService().getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getCreatedByUserId(),
                incident.getCreatedAt(),
                incident.getUpdatedAt(),
                incident.getVersion(),
                role);
    }
}
