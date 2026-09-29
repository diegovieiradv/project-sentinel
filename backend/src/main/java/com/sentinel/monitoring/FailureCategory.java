package com.sentinel.monitoring;

/**
 * Categorias de falha de uma checagem (Prompt 09) — o <em>porquê</em> por
 * trás do {@link MonitorOutcome}, sempre sem corpo de resposta, sem URL com
 * query string e sem detalhe de exceção.
 *
 * <p><b>Duas famílias, nunca misturadas:</b></p>
 * <ul>
 *   <li><b>Falha do alvo</b> (acompanha {@link MonitorOutcome#TARGET_FAILURE}):
 *       {@link #DNS}, {@link #CONNECT}, {@link #TIMEOUT}, {@link #TLS} e
 *       {@link #HTTP_ERROR} — o alvo respondeu mal ou não respondeu; o
 *       Sentinel funcionou como esperado.</li>
 *   <li><b>Falha interna do Sentinel</b> (acompanha
 *       {@link MonitorOutcome#SENTINEL_INTERNAL_FAILURE}): {@link #SSRF_REVALIDATION}
 *       (a proteção SSRF recusou o alvo na hora da conexão — é a proteção
 *       funcionando, não o alvo "fora do ar") e {@link #INTERNAL} (bug ou
 *       estado inesperado no próprio executor).</li>
 * </ul>
 *
 * <p>Saturação do pool de workers <b>não é categoria de falha</b>: é contada
 * como saturação interna pelo {@link MonitoringDispatcher} e o monitor
 * permanece due para o próximo tick.</p>
 */
public enum FailureCategory {

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
