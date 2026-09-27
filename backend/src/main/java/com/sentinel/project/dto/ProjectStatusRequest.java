package com.sentinel.project.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/status.
 *
 * <p>{@code Boolean} (objeto) + {@code @NotNull} de propósito: body ausente,
 * {@code {}} ou {@code {"active": null}} respondem {@code 400} — nunca um
 * {@code false} implícito vindo do default de primitivo. O servidor não
 * "adivinha" a intenção de ligar/desligar um projeto.</p>
 *
 * <p>Endpoint <b>idempotente</b>: ativar um projeto já ativo (ou desativar um
 * já inativo) devolve {@code 200} com o estado atual, sem escrita.</p>
 */
public record ProjectStatusRequest(@NotNull Boolean active) {
}
