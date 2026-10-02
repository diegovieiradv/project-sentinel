package com.sentinel.project.alert.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules/{ruleId}/activation.
 *
 * <p>{@code Boolean} (objeto) + {@code @NotNull} de propósito: body ausente,
 * {@code {}} ou {@code {"active": null}} respondem {@code 400} — nunca um
 * {@code false} implícito vindo do default de primitivo (mesmo critério de
 * {@code MonitorActivationRequest}).</p>
 *
 * <p><b>Endpoint idempotente e fail-safe:</b> ativar uma regra já ativa (ou
 * desativar uma já inativa) devolve {@code 200} com o estado atual, sem
 * escrita ({@code updatedAt} não avança). A transição
 * {@code false → true} exige pais ativos ({@code 409 PROJECT_INACTIVE} antes
 * de {@code 409 SERVICE_INACTIVE}); a transição {@code true → false} é sempre
 * permitida — inclusive com pais inativos, porque desligar é a direção
 * segura. Não existe DELETE: desativação é o soft-delete deste endpoint.</p>
 */
public record AlertRuleActivationRequest(@NotNull Boolean active) {
}
