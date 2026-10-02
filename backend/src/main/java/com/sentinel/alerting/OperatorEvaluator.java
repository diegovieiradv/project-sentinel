package com.sentinel.alerting;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;

/**
 * Comparação de UM valor agregado contra o threshold de uma regra (Prompt 13)
 * — o ÚNICO ponto em que {@link AlertOperator} é interpretado, para que a
 * semântica de {@code <}, {@code ≤}, {@code >}, {@code ≥} e {@code =} não se
 * repita nos cinco avaliadores de tipo.
 *
 * <p><b>Puro e estático:</b> sem estado, sem I/O, sem relógio — testável de
 * forma trivial e reutilizável por qualquer avaliador.</p>
 *
 * <p><b>Nulidade é chamada ANTES:</b> {@code null} significa "sem dado" e não
 * deve chegar aqui; quem recebe {@code null} devolve {@code NO_DATA}. Ainda
 * assim, defensivamente, {@code null} em qualquer argumento resulta em
 * {@code false} (não-match) — nunca uma NPE no meio do ciclo.</p>
 */
public final class OperatorEvaluator {

    private OperatorEvaluator() {
        // utilitário puro
    }

    /**
     * A condição vale? ({@code actual <operator> threshold}).
     *
     * @param operator  operador da regra (nunca {@code null})
     * @param actual    valor agregado observado na janela
     * @param threshold valor de corte da regra
     * @return {@code true} se a condição da regra está satisfeita
     */
    public static boolean matches(AlertOperator operator, BigDecimal actual, BigDecimal threshold) {
        if (operator == null || actual == null || threshold == null) {
            return false;
        }
        int comparison = actual.compareTo(threshold);
        return switch (operator) {
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case EQUAL -> comparison == 0;
        };
    }
}
