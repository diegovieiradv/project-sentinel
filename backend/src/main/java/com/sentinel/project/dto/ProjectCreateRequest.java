package com.sentinel.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/v1/organizations/{organizationId}/projects.
 *
 * <p>{@code organizationId} <b>não existe aqui de propósito</b>: o tenant vem
 * do path (resolvido e validado pelo {@code TenantInterceptor}) — um campo de
 * organização no body seria mass assignment de escopo. {@code active},
 * timestamps e id também ficam fora: são atributos de servidor.</p>
 *
 * <p>{@code @NotBlank} + {@code @Size(max=120)} espelham o CHECK
 * {@code projects_name_not_blank} e a coluna {@code VARCHAR(120)} da migration
 * V5; {@code @Size(max=1000)} espelha {@code description VARCHAR(1000)}.</p>
 *
 * <p>As mensagens dos {@code @Size} são <b>explícitas</b>: o default do
 * Bean Validation ("size must be between 1 and 120") é instável entre versões
 * e vaza texto de framework no contrato da API — aqui o contrato é
 * "must be at most N characters". {@code min} foi omitido de propósito:
 * comprimento mínimo já é coberto pelo {@code @NotBlank} e duplicar a
 * restrição faria string vazia devolver <b>duas</b> mensagens.</p>
 */
public record ProjectCreateRequest(
        @NotBlank @Size(max = 120, message = "must be at most 120 characters") String name,
        @Size(max = 1000, message = "must be at most 1000 characters") String description) {
}
