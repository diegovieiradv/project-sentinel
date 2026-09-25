package com.sentinel.organization.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Organization;
import com.sentinel.organization.model.Role;

/**
 * Contrato de saída de organização — entidade JPA nunca exposta cru.
 *
 * <p><b>{@code role}</b>: papel do usuário AUTENTICADO NA REQUISIÇÃO dentro
 * desta organização (no POST de criação, sempre {@code "ADMIN"} — o criador
 * recebe ADMIN junto com a organização, na mesma transação). O campo é
 * obrigatório porque não existe "visão neutra" da organização: toda leitura é
 * do ponto de vista de um membro.</p>
 *
 * <p>{@code role} é preferível a {@code yourRole}: mais curto, já usado em
 * {@code MemberResponse}, e o Javadoc documenta a semântica de "papel de quem
 * está perguntando".</p>
 */
public record OrganizationResponse(
        UUID id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static OrganizationResponse from(Organization organization, Role role) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getCreatedAt(),
                organization.getUpdatedAt(),
                role);
    }
}
