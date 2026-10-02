package com.sentinel.project.alert.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.ServiceStatus;

/**
 * Contrato de saída de UM alerta disparado (Prompt 13) — entidade JPA nunca
 * exposta cru.
 *
 * <p><b>Resultado + snapshot, lado a lado:</b> identidade e escopo
 * ({@code organizationId}/{@code projectId}/{@code serviceId} +
 * {@code alertRuleId}/{@code monitorId}), o ciclo de vida
 * ({@code status}, {@code startedAt}, {@code resolvedAt},
 * {@code lastEvaluatedAt}, {@code resolutionReason}), a evidência
 * ({@code message} do FIRING, {@code triggerValue}) e o SNAPSHOT da condição
 * que valia na época ({@code ruleName} + {@code ruleType}/{@code ruleOperator}/
 * thresholds/{@code ruleWindow} + {@code severity}).</p>
 *
 * <p><b>Por que o snapshot na resposta:</b> editar a regra depois do disparo
 * não reescreve o alerta — a UI mostra a condição real da época sem JOIN
 * retroativo e sem depender do estado atual da regra.</p>
 *
 * <p><b>{@code message} é a do disparo em QUALQUER desfecho:</b> a resolução
 * guarda o motivo em {@code resolutionReason} e nunca sobrescreve a mensagem
 * (correção da revisão). {@code resolutionReason} {@code null} = ainda
 * FIRING.</p>
 *
 * <p><b>{@code monitorId} nulo = Service-level</b> (métrica agregada do
 * serviço); com valor = escopo de monitor copiado da regra no disparo.</p>
 *
 * <p>Não existe campo {@code role} de mutação aqui: a rota é somente leitura;
 * o papel segue vindo no payload como nos demais contratos do projeto para a
 * UI decidir o que exibir.</p>
 */
public record AlertResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        UUID alertRuleId,
        UUID monitorId,
        String ruleName,
        AlertStatus status,
        AlertSeverity severity,
        AlertResolutionReason resolutionReason,
        Instant startedAt,
        Instant resolvedAt,
        Instant lastEvaluatedAt,
        BigDecimal triggerValue,
        String message,
        AlertRuleType ruleType,
        AlertOperator ruleOperator,
        BigDecimal ruleThresholdNumeric,
        ServiceStatus ruleThresholdStatus,
        EvaluationWindow ruleWindow,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static AlertResponse from(Alert alert, Role role) {
        return new AlertResponse(
                alert.getId(),
                alert.getService().getProject().getOrganization().getId(),
                alert.getService().getProject().getId(),
                alert.getService().getId(),
                alert.getAlertRule().getId(),
                alert.getMonitorId(),
                alert.getAlertRule().getName(),
                alert.getStatus(),
                alert.getSeverity(),
                alert.getResolutionReason(),
                alert.getStartedAt(),
                alert.getResolvedAt(),
                alert.getLastEvaluatedAt(),
                alert.getTriggerValue(),
                alert.getMessage(),
                alert.getRuleType(),
                alert.getRuleOperator(),
                alert.getRuleThresholdNumeric(),
                alert.getRuleThresholdStatus(),
                alert.getRuleWindow(),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                role);
    }
}
