package com.sentinel.project.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors.
 *
 * <p>{@code organizationId}, {@code projectId} e {@code serviceId} <b>não
 * existem aqui de propósito</b>: os três vêm do path (o tenant é validado pelo
 * {@code TenantInterceptor} e a cadeia é resolvida no service) — um campo de
 * escopo no body seria mass assignment. {@code type}, {@code active}, id e
 * timestamps também ficam fora: são atributos de servidor (o monitor nasce
 * {@code HTTP} e ativo; nenhum campo extra do JSON altera isso).</p>
 *
 * <p>{@code method}, {@code intervalSeconds} e {@code timeoutMillis} são
 * <b>opcionais</b> (default do servidor: {@code GET}, 60, 5000) e chegam como
 * tipo {@code String}/{@code Integer}: o domínio rejeita valor fora do
 * conjunto com a MENSAGEM do contrato ({@code method: must be GET or HEAD},
 * {@code intervalSeconds: must be between...}), não com o {@code 400} de
 * conversão de enum que um campo tipado produziria no bean validation.</p>
 *
 * <p>{@code url} é obrigatório no contrato mas <b>não</b> carrega
 * {@code @NotBlank}: a checagem pertence ao caminho de segurança — um body
 * sem URL cai no mesmo guard que rejeita alvos proibidos
 * ({@code MONITOR_TARGET_NOT_ALLOWED}), mantendo a ordem de checagens
 * documentada (SSRF antes do payload). {@code @NotBlank} + {@code @Size(120)}
 * em {@code name} espelham o CHECK {@code monitors_name_not_blank} e a coluna
 * {@code VARCHAR(120)} da migration V7.</p>
 */
public record MonitorCreateRequest(
        @NotBlank @Size(max = 120, message = "must be at most 120 characters") String name,
        String url,
        String method,
        Integer intervalSeconds,
        Integer timeoutMillis) {
}
