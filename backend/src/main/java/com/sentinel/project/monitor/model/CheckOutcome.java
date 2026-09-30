package com.sentinel.project.monitor.model;

/**
 * Desfecho PERSISTIDO de uma checagem (Prompt 10) — quatro valores, sem
 * sobreposição.
 *
 * <p><b>Relação com {@code MonitorOutcome} (Prompt 09):</b> o executor
 * continua sendo a fonte do desfecho bruto; o {@code MonitorCheckRecorder}
 * traduz 1:1 para este enum e acrescenta <b>um</b> refinamento de saúde —
 * a política de 3xx. Nenhuma semântica é duplicada: quem classifica a rede
 * é o executor, quem grava é o recorder.</p>
 *
 * <ul>
 *   <li>{@link #SUCCESS} — resposta <b>2xx</b>: o alvo respondeu e a
 *       aplicação respondeu dentro do esperado.</li>
 *   <li>{@link #DEGRADED_SIGNAL} — resposta <b>3xx</b>: o alvo respondeu
 *       (a checagem alcançou a rede), mas como os redirects nunca são
 *       seguidos ({@code Redirect.NEVER}) a resposta não é um sucesso pleno
 *       de aplicação — sinal de degradação, não de indisponibilidade.</li>
 *   <li>{@link #TARGET_FAILURE} — o Sentinel executou tudo certo e o
 *       <b>alvo</b> falhou (DNS, conexão, timeout, TLS ou 4xx/5xx — ver
 *       {@link CheckFailureCategory}).</li>
 *   <li>{@link #SENTINEL_INTERNAL_FAILURE} — problema do próprio Sentinel
 *       (revalidação SSRF recusou o alvo, bug inesperado). <b>Nunca</b>
 *       significa "alvo fora do ar" e <b>nunca</b> conta como evidência de
 *       saúde do alvo — é excluído da janela de avaliação (ver
 *       {@code docs/operational-state.md}).</li>
 * </ul>
 *
 * <p>Domínio idêntico ao CHECK {@code monitor_checks_outcome_check} da
 * migration V8: a enumeração é a validação na aplicação, o banco é a última
 * barreira.</p>
 */
public enum CheckOutcome {

    /** Resposta 2xx — sucesso pleno. */
    SUCCESS,

    /** Resposta 3xx — alcançou o alvo, mas não é sucesso pleno (redirect não seguido). */
    DEGRADED_SIGNAL,

    /** O alvo não respondeu como esperado (falha do alvo, não nossa). */
    TARGET_FAILURE,

    /** Falha interna do Sentinel (proteção SSRF ou erro inesperado) — nunca é evidência do alvo. */
    SENTINEL_INTERNAL_FAILURE
}
