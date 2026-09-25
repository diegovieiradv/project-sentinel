package com.sentinel.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de PATCH /api/v1/organizations/{organizationId}/members/{membershipId}.
 *
 * <p>Mesma razão de {@link AddMemberRequest} para {@code role} ser string:
 * valor fora do domínio → 400 VALIDATION_ERROR, nunca 500.</p>
 */
public record UpdateMemberRoleRequest(
        @NotBlank @Size(max = 32) String role) {
}
