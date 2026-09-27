package com.sentinel.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services.
 *
 * <p>{@code organizationId} e {@code projectId} <b>não existem aqui de
 * propósito</b>: os dois vêm do path (o tenant é validado pelo
 * {@code TenantInterceptor} e o projeto é resolvido no service) — um campo de
 * escopo no body seria mass assignment. {@code active}, {@code status},
 * timestamps e id também ficam fora: são atributos de servidor (o serviço
 * nasce ativo e {@code UNKNOWN}; nenhum campo extra do JSON altera isso).</p>
 *
 * <p>{@code @NotBlank} + {@code @Size(max=120)} espelham o CHECK
 * {@code services_name_not_blank} e a coluna {@code VARCHAR(120)} da migration
 * V6; {@code @Size(max=1000)} espelha {@code description VARCHAR(1000)}.</p>
 *
 * <p>As mensagens dos {@code @Size} são <b>explícitas</b> (mesmo critério de
 * {@code ProjectCreateRequest}): o contrato não depende do texto default do
 * Bean Validation.</p>
 */
public record ServiceCreateRequest(
        @NotBlank @Size(max = 120, message = "must be at most 120 characters") String name,
        @Size(max = 1000, message = "must be at most 1000 characters") String description) {
}
