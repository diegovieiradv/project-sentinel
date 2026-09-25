package com.sentinel.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/v1/organizations.
 *
 * <p>{@code @Size(max=128)} é o teto da coluna {@code VARCHAR(128)} da
 * migration V4 (e proteção contra payloads absurdos); {@code @NotBlank}
 * espelha o CHECK {@code organizations_name_not_blank} — nome vazio ou só
 * espaços nunca chega ao banco.</p>
 */
public record OrganizationCreateRequest(
        @NotBlank @Size(max = 128) String name) {
}
