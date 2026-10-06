package com.sentinel.project.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;

/**
 * Uma linha da lista de alertas em FIRING do painel.
 *
 * <p>Identidade + o que a UI precisa para pintar a linha sem segundo request:
 * serviço (já denormalizado pelo JOIN, sem N+1), regra que disparou,
 * severidade, quando começou e o incidente a que está associado
 * ({@code null} quando a automação/Prompt 14 não vinculou — o vínculo é
 * opcional e ausência dele não é erro).</p>
 */
public record ActiveAlertItem(
        UUID alertId,
        UUID serviceId,
        String serviceName,
        UUID ruleId,
        String ruleName,
        AlertSeverity severity,
        Instant firedAt,
        UUID incidentId) {
}
