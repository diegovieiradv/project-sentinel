package com.sentinel.project.monitor.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;
import com.sentinel.project.monitor.model.MonitorType;

/**
 * Contrato de saída de monitor — entidade JPA nunca exposta cru.
 *
 * <p><b>Só configuração, nunca resultado:</b> o payload tem identidade,
 * escopo ({@code organizationId}/{@code projectId}/{@code serviceId}), alvo e
 * parâmetros da checagem + {@code active}. Não existe (nem existirá por este
 * prompt) campo de latência, status HTTP, falha, última execução ou saúde —
 * isso é material de monitoramento, não de cadastro.</p>
 *
 * <p><b>{@code role}</b>: papel do usuário AUTENTICADO NA REQUISIÇÃO dentro
 * desta organização (mesma semântica de {@code ServiceResponse}).</p>
 *
 * <p>{@code nameNormalized} fica FORA do payload: é detalhe de implementação
 * da unicidade, não informação de domínio para o cliente.</p>
 */
public record MonitorResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        String name,
        MonitorType type,
        String url,
        MonitorMethod method,
        int intervalSeconds,
        int timeoutMillis,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static MonitorResponse from(Monitor monitor, Role role) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getService().getProject().getOrganization().getId(),
                monitor.getService().getProject().getId(),
                monitor.getService().getId(),
                monitor.getName(),
                monitor.getType(),
                monitor.getUrl(),
                monitor.getMethod(),
                monitor.getIntervalSeconds(),
                monitor.getTimeoutMillis(),
                monitor.isActive(),
                monitor.getCreatedAt(),
                monitor.getUpdatedAt(),
                role);
    }
}
