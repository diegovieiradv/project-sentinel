package com.sentinel.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload de POST /api/v1/auth/login.
 */
public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password) {
}
