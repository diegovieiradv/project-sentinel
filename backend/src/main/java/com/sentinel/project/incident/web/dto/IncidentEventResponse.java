package com.sentinel.project.incident.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;

/**
 * Contrato de saída de UMA linha da timeline de um incidente (Prompt 14) —
 * log <b>append-only</b>, somente leitura na API.
 *
 * <p>Cinco campos, todos nativos da linha: {@code id}/{@code incidentId},
 * o vocabulário fechado {@code type}, a frase determinística
 * {@code message}, o autor {@code actorUserId} (UUID ou {@code null} — nunca
 * e-mail) e o {@code createdAt} que ordena a timeline.</p>
 *
 * <p>Não existe {@code updatedAt}: o evento é fato histórico e não é
 * editável (não existe PATCH nem DELETE de evento em nenhum caminho).</p>
 */
public record IncidentEventResponse(
        UUID id,
        UUID incidentId,
        IncidentEventType type,
        String message,
        UUID actorUserId,
        Instant createdAt) {

    public static IncidentEventResponse from(IncidentEvent event) {
        return new IncidentEventResponse(
                event.getId(),
                event.getIncidentId(),
                event.getType(),
                event.getMessage(),
                event.getActorUserId(),
                event.getCreatedAt());
    }
}
