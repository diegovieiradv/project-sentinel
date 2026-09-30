package com.sentinel.project.monitor.model;

/**
 * Categorias de falha PERSISTIDAS de uma checagem (Prompt 10) — o
 * <em>porquê</em> por trás do {@link CheckOutcome}, sempre sem corpo de
 * resposta, sem URL com query string e sem detalhe de exceção.
 *
 * <p><b>Espelho 1:1 de {@code FailureCategory} (Prompt 09):</b> os sete
 * valores têm o MESMO nome e a MESMA semântica — o {@code MonitorCheckRecorder}
 * apenas traduz o enum do motor para este enum persistível. Os nomes são
 * idênticos de propósito: a migration V8 espelha este domínio no CHECK
 * {@code monitor_checks_failure_category_check} e qualquer divergência seria
 * um bug de tradução, não uma decisão de design.</p>
 *
 * <p><b>Duas famílias, nunca misturadas:</b></p>
 * <ul>
 *   <li><b>Falha do alvo</b> (acompanha {@link CheckOutcome#TARGET_FAILURE}):
 *       {@link #DNS}, {@link #CONNECT}, {@link #TIMEOUT}, {@link #TLS} e
 *       {@link #HTTP_ERROR}.</li>
 *   <li><b>Falha interna do Sentinel</b> (acompanha
 *       {@link CheckOutcome#SENTINEL_INTERNAL_FAILURE}):
 *       {@link #SSRF_REVALIDATION} (a proteção SSRF recusou o alvo — é a
 *       proteção funcionando) e {@link #INTERNAL} (bug ou estado
 *       inesperado).</li>
 * </ul>
 *
 * <p>Uma checagem sem falha ({@code SUCCESS}/{@code DEGRADED_SIGNAL}) tem
 * categoria {@code null} — a coluna é anulável na V8.</p>
 */
public enum CheckFailureCategory {

    /** Nome do alvo não resolve (ou o resolvedor devolveu erro). */
    DNS,

    /** Conexão TCP recusada/indisponível (porta fechada, route unreachable). */
    CONNECT,

    /** A checagem estourou {@code Monitor.timeoutMillis} (request timeout). */
    TIMEOUT,

    /** Falha no handshake TLS (certificado, protocolo, cipher). */
    TLS,

    /** Resposta recebida com status 4xx/5xx. */
    HTTP_ERROR,

    /** O {@code SsrfGuard} recusou o alvo imediatamente antes da conexão. */
    SSRF_REVALIDATION,

    /** Erro inesperado em código do Sentinel (nunca culpa do alvo). */
    INTERNAL
}
