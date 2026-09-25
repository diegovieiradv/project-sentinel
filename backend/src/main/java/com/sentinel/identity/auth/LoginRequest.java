package com.sentinel.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/v1/auth/login.
 *
 * <p>{@code @Size} apenas limita o tamanho (coluna e payload); a verificação
 * de credenciais é no service, com mensagem genérica única.</p>
 */
public record LoginRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String password) {
}
