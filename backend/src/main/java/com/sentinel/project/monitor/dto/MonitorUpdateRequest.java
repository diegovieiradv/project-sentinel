package com.sentinel.project.monitor.dto;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors/{monitorId}
 * — atualização <b>parcial</b>.
 *
 * <p>Campo ausente ({@code null}) = <b>não altera</b>. Por isso NÃO há
 * {@code @NotBlank} aqui (diferente do create): a validação de cada campo
 * presente é feita no service, porque só ele sabe distinguir "ausente" de
 * "em branco" (mesmo critério de {@code ServiceUpdateRequest}).</p>
 *
 * <p>DTO <b>fechado</b>: não existem {@code id}, {@code service},
 * {@code organizationId}, {@code projectId}, {@code type}, {@code active} nem
 * timestamps — ativação tem rota própria ({@code .../activation}) e o tipo é
 * invariante do cadastro.</p>
 *
 * <p>Quando {@code url} está presente ela é <b>revalidada pelo
 * {@code SsrfGuard}</b> antes de qualquer outra checagem de payload — um
 * update não é um caminho de bypass da proteção SSRF. A coerência
 * {@code timeout < interval * 1000} é recalculada sobre os valores EFETIVOS
 * (campo novo ou valor já persistido).</p>
 */
public record MonitorUpdateRequest(
        String name, String url, String method, Integer intervalSeconds, Integer timeoutMillis) {
}
