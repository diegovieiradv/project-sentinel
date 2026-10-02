package com.sentinel.alerting;

import java.math.BigDecimal;

/**
 * Desfecho da avaliação de UMA regra em UM ciclo (Prompt 13) — valor observado
 * + veredito, sem side-effect. O que fazer com o veredito é decisão do
 * {@link AlertLifecycleService}, nunca do avaliador.
 *
 * <p><b>Quatro vereditos, sem sobreposição:</b></p>
 * <ul>
 *   <li>{@link Verdict#FIRING} — a condição vale AGORA (disparar/manter
 *       aberto);</li>
 *   <li>{@link Verdict#HEALTHY} — a condição NÃO vale e há dado suficiente
 *       (resolver {@code AUTO} se houver alerta aberto);</li>
 *   <li>{@link Verdict#NO_DATA} — a janela não tem evidência para decidir
 *       (NUNCA dispara e NUNCA resolve — janela vazia não é saúde);</li>
 *   <li>{@link Verdict#ERROR} — a avaliação em si falhou (infraestrutura);
 *       também não dispara e não resolve: o ciclo seguinte decide.</li>
 * </ul>
 *
 * <p><b>Record imutável:</b> um resultado é um fato do ciclo, não um objeto
 * que evolui — nada nele é mutável depois de criado.</p>
 *
 * @param verdict o que o ciclo decidiu
 * @param value   valor numérico observado ({@code null} em SERVICE_STATUS,
 *                NO_DATA e ERROR)
 * @param detail  frase pronta para compor a mensagem do alerta
 *                ({@code ruleName + ": " + detail})
 */
public record EvaluationResult(Verdict verdict, BigDecimal value, String detail) {

    /** Veredito de um ciclo de avaliação. */
    public enum Verdict {
        /** Condição valendo — abrir ou manter o alerta. */
        FIRING,
        /** Condição não valendo com dado — resolver AUTO se houver alerta. */
        HEALTHY,
        /** Sem evidência na janela — nada muda. */
        NO_DATA,
        /** Falha da própria avaliação — nada muda (ciclo seguinte decide). */
        ERROR
    }

    public static EvaluationResult firing(BigDecimal value, String detail) {
        return new EvaluationResult(Verdict.FIRING, value, detail);
    }

    public static EvaluationResult healthy(BigDecimal value, String detail) {
        return new EvaluationResult(Verdict.HEALTHY, value, detail);
    }

    public static EvaluationResult noData(String detail) {
        return new EvaluationResult(Verdict.NO_DATA, null, detail);
    }

    public static EvaluationResult error(String detail) {
        return new EvaluationResult(Verdict.ERROR, null, detail);
    }
}
