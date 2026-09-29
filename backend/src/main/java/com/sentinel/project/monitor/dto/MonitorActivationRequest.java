package com.sentinel.project.monitor.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors/{monitorId}/activation.
 *
 * <p>{@code Boolean} (objeto) + {@code @NotNull} de propósito: body ausente,
 * {@code {}} ou {@code {"active": null}} respondem {@code 400} — nunca um
 * {@code false} implícito vindo do default de primitivo (mesmo critério de
 * {@code ServiceActivationRequest}).</p>
 *
 * <p><b>Endpoint idempotente e fail-safe:</b> ativar um monitor já ativo (ou
 * desativar um já inativo) devolve {@code 200} com o estado atual, sem
 * escrita. A transição {@code false → true} <b>revalida a URL pelo
 * {@code SsrfGuard}</b> (o alvo pode ter sido reclassificado como interno
 * desde a criação); a transição {@code true → false} é sempre permitida —
 * inclusive com projeto/serviço inativos, porque desligar um monitor é a
 * direção segura.</p>
 */
public record MonitorActivationRequest(@NotNull Boolean active) {
}
