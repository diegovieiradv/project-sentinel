package com.sentinel.project.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.model.Project;

/**
 * Contrato de saída de projeto — entidade JPA nunca exposta cru.
 *
 * <p><b>{@code role}</b>: papel do usuário AUTENTICADO NA REQUISIÇÃO dentro
 * desta organização (mesma semântica de {@code OrganizationResponse}): toda
 * leitura é de um ponto de vista de membro, não existe "visão neutra".</p>
 *
 * <p>{@code nameNormalized} fica FORA do payload: é detalhe de implementação
 * da unicidade, não informação de domínio para o cliente.</p>
 */
public record ProjectResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static ProjectResponse from(Project project, Role role) {
        return new ProjectResponse(
                project.getId(),
                project.getOrganization().getId(),
                project.getName(),
                project.getDescription(),
                project.isActive(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                role);
    }
}
