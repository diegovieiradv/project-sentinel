package com.sentinel.monitoring;

/**
 * Desfecho de uma checagem (Prompt 09) — três valores, sem sobreposição:
 *
 * <ul>
 *   <li>{@link #SUCCESS} — a requisição completou e o alvo respondeu 2xx/3xx
 *       (redirecionamentos <b>não</b> são seguidos: 3xx chega como
 *       resposta). Refinamento de 4xx/5xx vs. saúde real é escopo do
 *       Prompt 10.</li>
 *   <li>{@link #TARGET_FAILURE} — o Sentinel executou tudo certo e o <b>alvo</b>
 *       falhou (DNS, conexão, timeout, TLS ou status 4xx/5xx — ver
 *       {@link FailureCategory}).</li>
 *   <li>{@link #SENTINEL_INTERNAL_FAILURE} — problema do próprio Sentinel
 *       (revalidação SSRF recusou o alvo, bug inesperado). NUNCA significa
 *       "alvo fora do ar".</li>
 * </ul>
 *
 * <p>Saturação do executor não gera resultado: o dispatch é recusado antes de
 * executar e o monitor segue <em>due</em> (ver
 * {@link MonitoringDispatcher.DispatchOutcome}).</p>
 */
public enum MonitorOutcome {

    /** Requisição completou com status 2xx/3xx. */
    SUCCESS,

    /** O alvo não respondeu como esperado (falha do alvo, não nossa). */
    TARGET_FAILURE,

    /** Falha interna do Sentinel (proteção SSRF ou erro inesperado). */
    SENTINEL_INTERNAL_FAILURE
}
