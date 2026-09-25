package com.sentinel.organization.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Membership;
import com.sentinel.organization.model.Role;

/**
 * Contrato de saída de membro de uma organização (admin-only).
 *
 * <p>Este é o ÚNICO lugar da API onde o e-mail de um usuário aparece no
 * contexto de organização — por isso a listagem de membros exige ADMIN: expor
 * e-mails a qualquer VIEWER/OPERATOR seria vazamento de PII entre papéis.</p>
 *
 * <p>Nunca inclui password hash, tokens ou qualquer outro segredo.</p>
 */
public record MemberResponse(
        UUID membershipId,
        UUID userId,
        String name,
        String email,
        Role role,
        Instant createdAt) {

    public static MemberResponse from(Membership membership) {
        return new MemberResponse(
                membership.getId(),
                membership.getUser().getId(),
                membership.getUser().getName(),
                membership.getUser().getEmail(),
                membership.getRole(),
                membership.getCreatedAt());
    }
}
