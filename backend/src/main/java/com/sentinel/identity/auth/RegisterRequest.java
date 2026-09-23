package com.sentinel.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload de POST /api/v1/auth/register.
 * Validação estrutural via bean validation; política de senha (força) no service.
 */
public record RegisterRequest(
        @NotBlank String name,
        @Email @NotBlank String email,
        @NotBlank String password) {
}
