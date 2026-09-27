package com.sentinel.project.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;

/**
 * Contrato de saída de serviço — entidade JPA nunca exposta cru.
 *
 * <p><b>{@code organizationId} e {@code projectId}</b> saem por leitura do
 * caminho (service → projeto → organização): a resposta é sempre de um
 * serviço resolvido DENTRO do tenant corrente.</p>
 *
 * <p><b>{@code role}</b>: papel do usuário AUTENTICADO NA REQUISIÇÃO dentro
 * desta organização (mesma semântica de {@code ProjectResponse}).</p>
 *
 * <p><b>{@code status}</b> é o estado operacional declarado pelo servidor
 * (nesta versão sempre o valor persistido — {@code UNKNOWN} nasce aqui) e é
 * distinto de {@code active}: um descreve a saúde observada, o outro se o
 * cadastro está no catálogo.</p>
 *
 * <p>{@code nameNormalized} fica FORA do payload: é detalhe de implementação
 * da unicidade, não informação de domínio para o cliente.</p>
 */
public record ServiceResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String name,
        String description,
        boolean active,
        ServiceStatus status,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static ServiceResponse from(Service service, Role role) {
        return new ServiceResponse(
                service.getId(),
                service.getProject().getOrganization().getId(),
                service.getProject().getId(),
                service.getName(),
                service.getDescription(),
                service.isActive(),
                service.getStatus(),
                service.getCreatedAt(),
                service.getUpdatedAt(),
                role);
    }
}
