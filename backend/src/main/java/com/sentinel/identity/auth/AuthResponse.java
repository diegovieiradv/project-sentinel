package com.sentinel.identity.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Envelope de respostas de auth.
 * login → {user, tokenType:"session"}; register → {user} (tokenType omitido).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(UserResponse user, String tokenType) {
}
