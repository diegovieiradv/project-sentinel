package com.sentinel.project.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/activation.
 *
 * <p>{@code Boolean} (objeto) + {@code @NotNull} de propósito: body ausente,
 * {@code {}} ou {@code {"active": null}} respondem {@code 400} — nunca um
 * {@code false} implícito vindo do default de primitivo (mesmo critério de
 * {@code ProjectStatusRequest}).</p>
 *
 * <p><b>Por que {@code /activation} e não {@code /status}:</b> aqui se liga e
 * desliga a PARTICIPAÇÃO do serviço no catálogo ({@code active}); o status
 * operacional é outro eixo ({@code ServiceStatus}) que este prompt não
 * altera. Um caminho chamado {@code /status} com corpo
 * {@code {"active": ...}} prometeria exatamente a confusão que o domínio
 * quer evitar.</p>
 *
 * <p>Endpoint <b>idempotente</b>: ativar um serviço já ativo (ou desativar um
 * já inativo) devolve {@code 200} com o estado atual, sem escrita.</p>
 */
public record ServiceActivationRequest(@NotNull Boolean active) {
}
