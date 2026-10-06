package com.sentinel.project.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;

/**
 * Uma linha do feed de atividade recente do projeto (máximo 20).
 *
 * <p><b>Vocabulário fechado em três fontes</b> — a linha é uma UNIÃO das
 * três, já filtrada pela janela do painel:</p>
 * <ul>
 *   <li>alertas: {@code ALERT_FIRED} (em {@code started_at}) e
 *       {@code ALERT_RESOLVED} (em {@code resolved_at});</li>
 *   <li>incidentes: {@code INCIDENT_CREATED} e {@code INCIDENT_RESOLVED};</li>
 *   <li>timeline do Prompt 14: {@code INCIDENT_STATUS_CHANGED} e
 *       {@code INCIDENT_LINKED} (eventos {@code STATUS_CHANGED} e
 *       {@code ALERT_LINKED}).</li>
 * </ul>
 *
 * <p>{@code severity} é a severidade ATUAL do recurso dono da linha: no
 * alerta é o snapshot imutável do disparo; no incidente é a severidade
 * corrente (o incidente pode mudar de severidade) e, nos eventos de
 * timeline, ela vem do incidente dono do evento — presente em todos os
 * ramos da união. {@code null} fica reservado (defensivo do contrato) às
 * linhas em que não há severidade aplicável; nenhum ramo atual produz
 * esse caso.</p>
 *
 * <p>{@code resourceId}/{@code resourceType} identificam o recurso sem
 * fetch extra: alertas e incidentes são recursos distintos e o id é o que a
 * UI usa para navegar.</p>
 */
public record ActivityItem(
        ActivityType type,
        Instant timestamp,
        UUID serviceId,
        String serviceName,
        AlertSeverity severity,
        String title,
        UUID resourceId,
        ActivityResourceType resourceType) {
}
