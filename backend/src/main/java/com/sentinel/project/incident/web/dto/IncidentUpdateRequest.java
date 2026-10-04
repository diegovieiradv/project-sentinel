package com.sentinel.project.incident.web.dto;

import com.sentinel.project.alert.model.AlertSeverity;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/incidents/{incidentId}
 * — atualização <b>parcial</b>.
 *
 * <p>Campo ausente ({@code null}) = <b>não altera</b> — mesmo critério de
 * {@code ServiceUpdateRequest}: sem {@code @NotBlank}, a validação de cada
 * campo PRESENTE é do service (que sabe distinguir "ausente" de "em
 * branco"). Para {@code description}, {@code ""} <b>limpa</b> (vira
 * {@code null}, porque não existe "descrição vazia", existe "sem
 * descrição").</p>
 *
 * <p><b>Record resolve bem aqui</b> (ao contrário do
 * {@code AlertRuleUpdateRequest}): nenhum dos três campos precisa de três
 * estados — "ausente" e "{@code null} explícito" significam a MESMA coisa
 * ("não altera").</p>
 *
 * <p>DTO fechado: sem {@code id}, {@code serviceId}, {@code status},
 * {@code startedAt}, {@code resolvedAt}, {@code createdByUserId}, timestamps
 * nem {@code version} (todos servidor-owned — o estado evolui só pela rota
 * {@code /status}, {@code startedAt} é imutável e {@code resolvedAt} nasce da
 * transição). Chaves <b>desconhecidas</b> no JSON são <b>ignoradas</b>, nunca
 * 400 ({@code FAIL_ON_UNKNOWN_PROPERTIES} desabilitado pela
 * auto-configuração do Spring Boot; mesma convenção de
 * {@code ServiceUpdateRequest}).</p>
 *
 * <p>Sem {@code @Valid}: o service distingue ausente de branco e aplica o
 * limite da coluna com a mensagem {@code title: …}/{@code description: …}.</p>
 */
public record IncidentUpdateRequest(String title, String description, AlertSeverity severity) {
}
