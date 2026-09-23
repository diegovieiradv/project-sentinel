package com.sentinel.identity.auth;

import java.util.UUID;

import com.sentinel.identity.user.User;

/**
 * Contrato de saída de usuário — nunca expõe password/passwordHash.
 */
public record UserResponse(UUID id, String name, String email) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
