package com.sentinel.project.alert.model;

/**
 * Operador de comparação de uma regra de alerta (Prompt 12) — exatamente cinco
 * variantes, semântica matemática padrão e símbolo único por valor.
 *
 * <p><b>Não existe operador "contém" / "regex" / "mudou de":</b> o Alert
 * Engine futuro (Prompt 13) avalia um ÚNICO valor agregado da janela contra um
 * threshold — os cinco operadores cobrem toda a matriz
 * {@link AlertRuleType} × operador sem abrir o domínio para predicados
 * arbitrários.</p>
 *
 * <p><b>Matriz de permitidos</b> (validada na aplicação pela
 * {@code AlertRuleValidationMatrix} + CHECK {@code alert_rules_operator_check}
 * da migration V9):</p>
 * <ul>
 *   <li>{@link #LESS_THAN}/{@link #LESS_THAN_OR_EQUAL} → percentuais
 *       ({@link AlertRuleType#AVAILABILITY}, {@link AlertRuleType#SUCCESS_RATE});</li>
 *   <li>{@link #GREATER_THAN}/{@link #GREATER_THAN_OR_EQUAL} → latência e
 *       contagem;</li>
 *   <li>{@link #EQUAL} → contagem e {@link AlertRuleType#SERVICE_STATUS}.</li>
 * </ul>
 */
public enum AlertOperator {

    /** Menor que ({@code <}). */
    LESS_THAN("<"),

    /** Menor ou igual ({@code ≤}). */
    LESS_THAN_OR_EQUAL("≤"),

    /** Maior que ({@code >}). */
    GREATER_THAN(">"),

    /** Maior ou igual ({@code ≥}). */
    GREATER_THAN_OR_EQUAL("≥"),

    /** Igual ({@code =}) — único operador de SERVICE_STATUS. */
    EQUAL("=");

    private final String symbol;

    AlertOperator(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Símbolo legível da UI ({@code <}, {@code ≤}, {@code >}, {@code ≥},
     * {@code =}) — mesma fonte para a linha da tabela e a frase de condição;
     * a regra de negócio usa o enum, nunca a string.
     */
    public String symbol() {
        return symbol;
    }
}
