package com.sentinel.project.alert.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.ServiceStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules.
 *
 * <p>{@code organizationId}, {@code projectId} e {@code serviceId} <b>não
 * existem aqui de propósito</b>: os três vêm do path (o tenant é validado pelo
 * {@code TenantInterceptor} e a cadeia é resolvida no service) — um campo de
 * escopo no body seria mass assignment. {@code active}, timestamps e id também
 * ficam fora: são atributos de servidor (a regra nasce ativa).</p>
 *
 * <p><b>{@code @Valid} roda só no POST</b> (mesmo arranjo de
 * {@code MonitorCreateRequest}): o create tem campos obrigatórios fixos —
 * {@code @NotBlank} no nome espelha o CHECK {@code alert_rules_name_not_blank}
 * e {@code @Size(max=120)} a coluna {@code VARCHAR(120)}; as mensagens dos
 * {@code @Size} são explícitas, para o contrato não depender do texto default
 * do Bean Validation. Os {@code @NotNull} de type/operator/window/severity
 * cobrem os itens 6–9 da validação de domínio.</p>
 *
 * <p><b>Sem {@code @DecimalMin/@DecimalMax} no threshold:</b> a faixa é POR
 * TIPO (percentual 0..100, latência &gt; 0 sem teto, contagem inteira ≥ 0) e
 * a exclusividade numérico × status depende do tipo — uma restrição de faixa
 * aqui rejeitaria latência legítima como {@code 800 ms} antes do service.
 * A autoridade é o {@code AlertRuleValidationMatrix} (create e update), que
 * devolve {@code 400 VALIDATION_ERROR} com mensagem específica.</p>
 *
 * <p>{@code thresholdNumeric} e {@code thresholdStatus} são anuláveis de
 * propósito: qual dos dois é obrigatório também é decisão do tipo.</p>
 *
 * <p><b>{@code autoCreateIncident} (Prompt 15):</b> opt-in de automação
 * Alert → Incident. {@code Boolean} (wrapper) — não {@code boolean}
 * primitivo: com Jackson 3 ({@code FAIL_ON_NULL_FOR_PRIMITIVES} ligado por
 * padrão) uma chave AUSENTE no JSON vira {@code null} e um primitivo
 * rejeitaria todo payload legado com 400 "Malformed request body" (contrato
 * de compatibilidade: criar regra sem a chave continua o mesmo comportamento
 * do Prompt 12). {@code null}/ausente é normalizado para {@code false} no
 * service, que É o default do banco (V12) — mesmo arranjo do
 * {@code Boolean active} do {@code AlertRuleActivationRequest}. Sem
 * {@code @NotNull}: não é obrigatório, só precisa ser booleano quando
 * presente (JSON {@code "autoCreateIncident": "sim"} → 400 de
 * desserialização, mesmo contrato dos demais campos tipados).</p>
 */
public record AlertRuleCreateRequest(
        @NotBlank @Size(max = 120, message = "must be at most 120 characters") String name,
        @NotNull AlertRuleType type,
        @NotNull AlertOperator operator,
        BigDecimal thresholdNumeric,
        ServiceStatus thresholdStatus,
        @NotNull EvaluationWindow window,
        @NotNull AlertSeverity severity,
        UUID monitorId,
        Boolean autoCreateIncident) {
}
