package com.sentinel.project.alert.model;

/**
 * Tipo de condição de uma regra de alerta (Prompt 12) — exatamente cinco
 * variantes, cada uma reutilizando a MESMA semântica de métrica entregue pelo
 * Prompt 11 (disponibilidade, success rate, latência média, contagem de falhas
 * de alvo e estado operacional do serviço).
 *
 * <p><b>Por que fechado assim (YAGNI):</b> o domínio é o que o sistema já
 * mede; criar tipos "genéricos" (fórmula livre, métrica arbitrária) agora só
 * empurraria a complexidade para depois. Um tipo novo entra com entrada aqui +
 * CHECK {@code alert_rules_type_check} da migration V9 + ramificação na
 * {@code AlertRuleValidationMatrix} — nunca como string livre no payload.</p>
 *
 * <p><b>Threshold varia por tipo</b> (exclusividade mútua validada na
 * aplicação, amarrada também pelos CHECKs da V9): os quatro tipos numéricos
 * usam {@code thresholdNumeric}; {@link #SERVICE_STATUS} usa
 * {@code thresholdStatus} e rejeita número.</p>
 *
 * <p>Cada tipo também restringe os operadores permitidos (matriz
 * type × operator) — ver {@code AlertRuleValidationMatrix}.</p>
 */
public enum AlertRuleType {

    /** Disponibilidade do serviço na janela, em percentual 0–100 (operações {@code <}, {@code ≤}). */
    AVAILABILITY,

    /** Taxa de sucesso das checagens na janela, em percentual 0–100 (operações {@code <}, {@code ≤}). */
    SUCCESS_RATE,

    /** Latência média da janela, em milissegundos {@code > 0} (operações {@code >}, {@code ≥}). */
    AVERAGE_LATENCY,

    /** Contagem de falhas de alvo na janela, inteiro {@code ≥ 0} (operações {@code >}, {@code ≥}, {@code =}). */
    TARGET_FAILURE_COUNT,

    /**
     * Estado operacional do serviço na janela — threshold É o status
     * ({@code DEGRADED} ou {@code UNAVAILABLE}), nunca número, e o único
     * operador é {@code EQUAL}.
     */
    SERVICE_STATUS
}
