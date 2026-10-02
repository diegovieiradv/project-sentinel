package com.sentinel.project.alert.validation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import com.sentinel.error.ApiException;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.springframework.http.HttpStatus;

/**
 * Validação de domínio CENTRALIZADA das regras de alerta (Prompt 12) — o
 * coração da integridade da feature. Todo create e todo update passam por
 * aqui ANTES de qualquer escrita.
 *
 * <p>Três garantias, todas em {@code 400 VALIDATION_ERROR} com mensagem
 * específica (nunca 500, nunca constraint estourando no banco):</p>
 * <ol>
 *   <li><b>Matriz type × operator</b> (Seção 17 do prompt): cada tipo só
 *       aceita os operadores com semântica correta — por exemplo
 *       {@code AVAILABILITY} só compara para baixo ({@code <}, {@code ≤}) e
 *       {@code SERVICE_STATUS} só {@code =};</li>
 *   <li><b>Threshold por tipo</b> (Seções 11–15): percentuais em [0, 100]
 *       com no máximo 2 casas decimais; latência &gt; 0 ms (fração permitida,
 *       até 4 casas — cabe no {@code NUMERIC(19,4)}); contagem inteira ≥ 0
 *       (escala 0);</li>
 *   <li><b>Exclusividade mútua</b> (Seção 15): {@code thresholdNumeric} e
 *       {@code thresholdStatus} NUNCA coexistem — status apenas em
 *       {@code SERVICE_STATUS} (e só {@code DEGRADED}/{@code UNAVAILABLE}:
 *       alertar contra {@code OPERATIONAL}/{@code UNKNOWN} não faz sentido),
 *       número apenas nos quatro tipos numéricos.</li>
 * </ol>
 *
 * <p><b>Por que classe estática e não bean Spring:</b> a validação é função
 * pura dos parâmetros (sem estado, sem dependência de repositório ou
 * contexto) — testável unitariamente sem container, como exigem os testes
 * 21.2–21.6 do plano. O {@code AlertRuleService} resolve antes os valores
 * EFETIVOS (no update, campos ausentes viram os valores persistidos) e chama
 * {@link #validate} com o conjunto completo — create e update passam pela
 * MESMA regra, sem caminho de bypass.</p>
 *
 * <p><b>O que NÃO é validação aqui:</b> unicidade de nome (service +
 * constraint da V9), RBAC, cadeia de tenant, pais ativos e ownership de
 * monitor pertencem ao {@code AlertRuleService}; a forma dos enums (valor
 * inválido no JSON) é rejeitada pelo desserializador com o mesmo 400.</p>
 */
public final class AlertRuleValidationMatrix {

    /**
     * Matriz type × operator (Seção 17): a autoridade sobre quais operadores
     * cada tipo aceita. Invariante do prompt — nenhum outro operador é
     * aceitável, mesmo que "faça sentido" em outro contexto.
     */
    private static final Map<AlertRuleType, Set<AlertOperator>> ALLOWED_OPERATORS = Map.of(
            AlertRuleType.AVAILABILITY,
            Set.of(AlertOperator.LESS_THAN, AlertOperator.LESS_THAN_OR_EQUAL),
            AlertRuleType.SUCCESS_RATE,
            Set.of(AlertOperator.LESS_THAN, AlertOperator.LESS_THAN_OR_EQUAL),
            AlertRuleType.AVERAGE_LATENCY,
            Set.of(AlertOperator.GREATER_THAN, AlertOperator.GREATER_THAN_OR_EQUAL),
            AlertRuleType.TARGET_FAILURE_COUNT,
            Set.of(
                    AlertOperator.GREATER_THAN,
                    AlertOperator.GREATER_THAN_OR_EQUAL,
                    AlertOperator.EQUAL),
            AlertRuleType.SERVICE_STATUS,
            Set.of(AlertOperator.EQUAL));

    /**
     * Únicos status aceitos como threshold de alerta (Seção 26):
     * {@code OPERATIONAL} e {@code UNKNOWN} são proibidos de propósito —
     * regra contra "tudo saudável" não é alerta, é ruído.
     */
    private static final Set<ServiceStatus> ALERTABLE_STATUSES =
            Set.of(ServiceStatus.DEGRADED, ServiceStatus.UNAVAILABLE);

    /** Percentual: 0..100 com no máximo 2 casas decimais (ex.: 99.9). */
    private static final int PERCENT_MAX_SCALE = 2;

    /**
     * Latência: fração permitida (ex.: 800.5 ms) limitada às 4 casas do
     * {@code NUMERIC(19,4)} da migration V9.
     */
    private static final int LATENCY_MAX_SCALE = 4;

    private AlertRuleValidationMatrix() {
        // utilitário estático: instanciar nunca faz sentido
    }

    /**
     * Validação COMPLETA de uma condição — o que create e update chamam.
     *
     * @param type             tipo da condição (não nulo)
     * @param operator         operador (não nulo)
     * @param thresholdNumeric threshold numérico ou {@code null} para SERVICE_STATUS
     * @param thresholdStatus  status do threshold ou {@code null} para os tipos numéricos
     * @throws ApiException {@code 400 VALIDATION_ERROR} na primeira violação
     */
    public static void validate(
            AlertRuleType type,
            AlertOperator operator,
            BigDecimal thresholdNumeric,
            ServiceStatus thresholdStatus) {
        validateOperator(type, operator);
        validateThreshold(type, thresholdNumeric, thresholdStatus);
    }

    /**
     * Matriz type × operator isolada (também usada pelos testes unitários).
     *
     * <p>Mensagem no formato do plano:
     * {@code "operator: LESS_THAN not allowed for SERVICE_STATUS"}.</p>
     */
    public static void validateOperator(AlertRuleType type, AlertOperator operator) {
        if (type == null) {
            throw validationError("type: must not be null");
        }
        if (operator == null) {
            throw validationError("operator: must not be null");
        }
        if (!ALLOWED_OPERATORS.get(type).contains(operator)) {
            throw validationError(
                    "operator: " + operator.name() + " not allowed for " + type.name());
        }
    }

    /**
     * Threshold por tipo + exclusividade mútua isoladas (também usada pelos
     * testes unitários 21.2–21.6).
     *
     * <p>Ordem das checagens (contrato das mensagens): em SERVICE_STATUS,
     * primeiro a proibição do número, depois a obrigatoriedade e o domínio
     * do status; nos tipos numéricos, primeiro a proibição do status, depois
     * a obrigatoriedade do número e por fim a faixa do tipo.</p>
     */
    public static void validateThreshold(
            AlertRuleType type, BigDecimal thresholdNumeric, ServiceStatus thresholdStatus) {
        if (type == null) {
            throw validationError("type: must not be null");
        }
        if (type == AlertRuleType.SERVICE_STATUS) {
            if (thresholdNumeric != null) {
                throw validationError("thresholdNumeric: must be null for SERVICE_STATUS");
            }
            if (thresholdStatus == null) {
                throw validationError("thresholdStatus: required for SERVICE_STATUS");
            }
            if (!ALERTABLE_STATUSES.contains(thresholdStatus)) {
                throw validationError(
                        "thresholdStatus: must be DEGRADED or UNAVAILABLE for alerting");
            }
            return;
        }
        if (thresholdStatus != null) {
            throw validationError("thresholdStatus: must be null for " + type.name());
        }
        if (thresholdNumeric == null) {
            throw validationError("thresholdNumeric: required for " + type.name());
        }
        switch (type) {
            case AVAILABILITY, SUCCESS_RATE -> validatePercentage(type, thresholdNumeric);
            case AVERAGE_LATENCY -> validateLatency(thresholdNumeric);
            case TARGET_FAILURE_COUNT -> validateFailureCount(thresholdNumeric);
            default -> throw validationError("type: unsupported " + type.name());
        }
    }

    // --- faixas por tipo ----------------------------------------------------

    /** Percentual 0 ≤ x ≤ 100, escala ≤ 2 (ex.: 99.95). */
    private static void validatePercentage(AlertRuleType type, BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw validationError(
                    "thresholdNumeric: must be between 0 and 100 for " + type.name());
        }
        if (value.stripTrailingZeros().scale() > PERCENT_MAX_SCALE) {
            throw validationError(
                    "thresholdNumeric: must have at most "
                            + PERCENT_MAX_SCALE
                            + " decimal places for "
                            + type.name());
        }
    }

    /** Latência em ms: x > 0, fração permitida com escala ≤ 4. */
    private static void validateLatency(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw validationError(
                    "thresholdNumeric: must be greater than 0 for "
                            + AlertRuleType.AVERAGE_LATENCY.name());
        }
        if (value.stripTrailingZeros().scale() > LATENCY_MAX_SCALE) {
            throw validationError(
                    "thresholdNumeric: must have at most "
                            + LATENCY_MAX_SCALE
                            + " decimal places for "
                            + AlertRuleType.AVERAGE_LATENCY.name());
        }
    }

    /** Contagem de falhas: inteiro (escala 0) ≥ 0. */
    private static void validateFailureCount(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw validationError(
                    "thresholdNumeric: must be greater than or equal to 0 for "
                            + AlertRuleType.TARGET_FAILURE_COUNT.name());
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw validationError(
                    "thresholdNumeric: must be an integer for "
                            + AlertRuleType.TARGET_FAILURE_COUNT.name());
        }
    }

    // --- apoio --------------------------------------------------------------

    private static ApiException validationError(String message) {
        return new ApiException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
