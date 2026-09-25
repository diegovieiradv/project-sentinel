package com.sentinel.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/v1/auth/register.
 * Validação estrutural via bean validation; política de senha (força) no service.
 *
 * <p>{@code @Size} define apenas o TETO (limites da coluna e proteção contra
 * payloads absurdos) — a exigência mínima de força continua sendo responsabilidade
 * de {@code UserService} (mensagem própria, contrato público).</p>
 */
public record RegisterRequest(
        @NotBlank @Size(max = 255) String name,
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String password) {
}
