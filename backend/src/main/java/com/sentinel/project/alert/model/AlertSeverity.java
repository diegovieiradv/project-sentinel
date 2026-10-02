package com.sentinel.project.alert.model;

/**
 * Severidade de uma regra de alerta (Prompt 12) — exatamente quatro níveis,
 * semântica fixa e label textual.
 *
 * <p><b>SEV1–SEV4 não são "prioridade editável":</b> o rótulo e a semântica
 * são parte do contrato (Crítico → Alto → Moderado → Baixo), persistidos como
 * {@code VARCHAR(10)} + CHECK {@code alert_rules_severity_check} da migration
 * V9. A UI mostra SEMPRE o texto junto da cor ({@code Critical},
 * {@code High}, {@code Moderate}, {@code Low}) — cor sozinha nunca é o
 * sinal.</p>
 */
public enum AlertSeverity {

    /**
     * Crítico — indisponibilidade total, SLA crítico. Label UI: {@code Critical}.
     */
    SEV1,

    /** Alto — degradação severa, spike de latência. Label UI: {@code High}. */
    SEV2,

    /** Moderado — warning, threshold próximo de estourar. Label UI: {@code Moderate}. */
    SEV3,

    /** Baixo — informativo, contagem baixa. Label UI: {@code Low}. */
    SEV4
}
