package com.sentinel.project.alert.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.ServiceStatus;

/**
 * Contrato de saída de regra de alerta — entidade JPA nunca exposta cru.
 *
 * <p><b>Só configuração, nunca resultado:</b> o payload tem identidade,
 * escopo ({@code organizationId}/{@code projectId}/{@code serviceId} +
 * {@code monitorId} opcional), a condição (type/operator/thresholds/window),
 * severidade, {@code active} e a flag {@code autoCreateIncident} (Prompt 15 —
 * opt-in de automação, também configuração). Não existe campo de "última
 * avaliação", "disparado em" ou estado de alerta/incidente — nenhum resultado
 * existe neste payload (a avaliação é do Alert Engine, Prompt 13; o
 * incidente nasce no serviço deles, nunca como coluna da regra).</p>
 *
 * <p><b>{@code monitorId}</b> {@code null} = Service-level (métrica agregada
 * do serviço); com valor = Monitor-level (avalia a métrica daquele monitor do
 * MESMO serviço). O nome do monitor não vem aqui — a UI resolve o nome via
 * listagem de monitores já carregada (o monitor é LAZY na entidade, então só
 * o id é lido, sem select extra por linha na listagem).</p>
 *
 * <p><b>{@code role}</b>: papel do usuário AUTENTICADO NA REQUISIÇÃO dentro
 * desta organização (mesma semântica de {@code MonitorResponse}).</p>
 *
 * <p>{@code nameNormalized} fica FORA do payload: é detalhe de implementação
 * da unicidade, não informação de domínio para o cliente.</p>
 */
public record AlertRuleResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        UUID monitorId,
        String name,
        AlertRuleType type,
        AlertOperator operator,
        BigDecimal thresholdNumeric,
        ServiceStatus thresholdStatus,
        EvaluationWindow window,
        AlertSeverity severity,
        boolean active,
        boolean autoCreateIncident,
        Instant createdAt,
        Instant updatedAt,
        Role role) {

    public static AlertRuleResponse from(AlertRule rule, Role role) {
        return new AlertRuleResponse(
                rule.getId(),
                rule.getService().getProject().getOrganization().getId(),
                rule.getService().getProject().getId(),
                rule.getService().getId(),
                rule.getMonitor() == null ? null : rule.getMonitor().getId(),
                rule.getName(),
                rule.getType(),
                rule.getOperator(),
                rule.getThresholdNumeric(),
                rule.getThresholdStatus(),
                rule.getWindow(),
                rule.getSeverity(),
                rule.isActive(),
                rule.isAutoCreateIncident(),
                rule.getCreatedAt(),
                rule.getUpdatedAt(),
                role);
    }
}
