package com.sentinel.project.incident.model;

/**
 * Estado de um incidente (Prompt 14) — máquina de estados FECHADA e pura
 * (sem Spring, sem JPA, sem exceção de domínio): os quatro estados do
 * ciclo de vida manual e as seis transições permitidas.
 *
 * <pre>
 *   OPEN ──────────► INVESTIGATING ─────► IDENTIFIED ─────► RESOLVED
 *    │  └────────────► IDENTIFIED ───────────┘                (terminal)
 *    └────────────────────────► RESOLVED
 * </pre>
 *
 * <p><b>Regras fixadas aqui (critérios 13–22):</b></p>
 * <ul>
 *   <li>{@link #RESOLVED} é TERMINAL — nenhuma transição sai dele, nem de
 *       volta para OPEN (reabrir é um NOVO incidente);</li>
 *   <li>não existe transição para TRÁS entre os estados de trabalho
 *       (IDENTIFIED → INVESTIGATING e INVESTIGATING → OPEN não existem) —
 *       o fluxo é de avanço;</li>
 *   <li>autotransição ({@code X → X}) nunca é válida: PATCH com o MESMO
 *       status responde {@code 409 INCIDENT_STATUS_TRANSITION} (não é um
 *       no-op silencioso — critério 17);</li>
 *   <li>a resolução é a ÚNICA transição que dispara {@code resolvedAt} no
 *       serviço (a coluna nasce {@code NULL} e o CHECK
 *       {@code incidents_lifecycle_check} da V11 amarra as duas).</li>
 * </ul>
 *
 * <p><b>Por que no enum e não no service:</b> transição é regra de domínio,
 * não de orquestração — os testes de máquina de estados rodam puros (sem
 * contexto Spring) e o serviço só traduz {@code false} em
 * {@code 409 INCIDENT_STATUS_TRANSITION}.</p>
 */
public enum IncidentStatus {

    OPEN,
    INVESTIGATING,
    IDENTIFIED,
    RESOLVED;

    /** {@code true} quando o incidente já foi encerrado (imutável na API). */
    public boolean isResolved() {
        return this == RESOLVED;
    }

    /**
     * @param target estado pedido pelo PATCH
     * @return {@code true} se a transição existe no grafo acima (e não é
     *         autotransição nem saída de {@link #RESOLVED})
     */
    public boolean canTransitionTo(IncidentStatus target) {
        if (target == null || target == this) {
            return false;
        }
        if (this == RESOLVED) {
            return false;
        }
        return switch (this) {
            case OPEN -> target == INVESTIGATING || target == IDENTIFIED || target == RESOLVED;
            case INVESTIGATING -> target == IDENTIFIED || target == RESOLVED;
            case IDENTIFIED -> target == RESOLVED;
            case RESOLVED -> false;
        };
    }
}
