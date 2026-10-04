package com.sentinel.project.incident.web.dto;

import java.time.Instant;

import com.sentinel.project.alert.model.AlertSeverity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/incidents.
 *
 * <p>{@code organizationId}, {@code projectId} e {@code serviceId} <b>não
 * existem aqui de propósito</b>: os três vêm do path (o tenant é validado pelo
 * {@code TenantInterceptor} e a cadeia é resolvida no service) — um campo de
 * escopo no body seria mass assignment. {@code status}, {@code source},
 * {@code resolvedAt}, {@code createdByUserId}, timestamps, id e version
 * também ficam fora: são atributos de servidor (o incidente nasce
 * {@code OPEN}, com {@code resolvedAt} nulo, e o autor vem do
 * {@code TenantContext}). {@code source} é o caso mais delicado desde o
 * Prompt 15: origem é fato histórico — nasce {@code MANUAL} no POST e só a
 * automação interna escreve {@code ALERT_AUTOMATION} (o JSON a ignora, como
 * os demais, porque {@code FAIL_ON_UNKNOWN_PROPERTIES} está desabilitado).</p>
 *
 * <p><b>{@code @Valid} roda só no POST</b> (mesmo arranjo de
 * {@code AlertRuleCreateRequest}): as mensagens dos {@code @Size} são
 * explícitas para o contrato não depender do texto default do Bean
 * Validation. O service repete as checagens de domínio (trim + limite da
 * coluna) porque o PATCH não tem {@code @Valid}.</p>
 *
 * <p><b>{@code startedAt} anulável:</b> quando ausente o servidor usa o
 * relógio do domínio ("agora"); quando presente precisa caber na janela
 * aceitável (não mais que 5 minutos no futuro, não mais que 30 dias no
 * passado) — validado no service, que tem o relógio.</p>
 */
public record IncidentCreateRequest(
        @NotBlank @Size(max = 200, message = "must be at most 200 characters") String title,
        @Size(max = 4000, message = "must be at most 4000 characters") String description,
        @NotNull AlertSeverity severity,
        Instant startedAt) {
}
