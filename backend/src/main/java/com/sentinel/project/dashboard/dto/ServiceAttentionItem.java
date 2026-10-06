package com.sentinel.project.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.model.ServiceStatus;

/**
 * Serviço que pede atenção agora — degradado/indisponível, ou com alerta
 * FIRING, ou com incidente ativo.
 *
 * <p>A lista é resolvida NO banco com uma única consulta (EXISTS + subqueries
 * limitadas, ordenação e {@code LIMIT 20}): nenhum desses predicados é
 * reconstruído em Java por serviço (o que seria N+1).</p>
 *
 * <p>{@code highestSeverity} é a MAIOR severidade ativa entre o alerta
 * FIRING mais grave e o incidente ativo mais grave (SEV1 &gt; SEV4) e só é
 * {@code null} quando a linha entrou por {@code status} — serviço
 * UNAVAILABLE/DEGRADED sem alerta nem incidente não tem severidade a
 * reportar (o estado do serviço já está em {@code status}).</p>
 */
public record ServiceAttentionItem(
        UUID serviceId,
        String serviceName,
        ServiceStatus status,
        AlertSeverity highestSeverity,
        boolean hasActiveIncident,
        Instant statusChangedAt) {
}
