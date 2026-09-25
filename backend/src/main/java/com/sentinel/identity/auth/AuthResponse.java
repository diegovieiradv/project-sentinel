package com.sentinel.identity.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Envelope de respostas de auth (Prompt 03).
 *
 * <p>register → {user}; login/refresh → {user, accessToken, tokenType:"Bearer"}.</p>
 *
 * <p>O refresh token NUNCA aparece aqui: ele viaja apenas no cookie HttpOnly
 * (Path=/api/v1/auth), invisível para o JavaScript e para o corpo do JSON.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(UserResponse user, String accessToken, String tokenType) {

    public static final String TOKEN_TYPE_BEARER = "Bearer";

    public static AuthResponse registered(UserResponse user) {
        return new AuthResponse(user, null, null);
    }

    public static AuthResponse authenticated(UserResponse user, String accessToken) {
        return new AuthResponse(user, accessToken, TOKEN_TYPE_BEARER);
    }
}
