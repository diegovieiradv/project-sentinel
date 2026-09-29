package com.sentinel.monitoring;

/**
 * Revalidação de alvo imediatamente antes de cada conexão (Prompt 09).
 *
 * <p><b>Por que uma interface:</b> em produção quem implementa é
 * {@link SsrfGuardMonitorTargetValidator}, que delega ao {@code SsrfGuard}
 * REAL do Prompt 08 — a política de ranges/privados tem fonte única, sem
 * duplicação e sem nenhuma flag de bypass. A interface existe apenas para
 * injeção de dependência nos testes de transporte (um servidor local do JDK
 * seria bloqueado pelo guard, como deve ser), exatamente como o
 * {@code DnsResolver} já era injetável.</p>
 *
 * <p><b>Contrato:</b> retorna silenciosamente quando o alvo é permitido;
 * lança a mesma exceção do guard (no caso de produção, a
 * {@code ApiException 400 MONITOR_TARGET_NOT_ALLOWED}) quando o alvo deve ser
 * recusado. O {@link HttpMonitorExecutor} traduz essa recusa em
 * {@link MonitorOutcome#SENTINEL_INTERNAL_FAILURE} +
 * {@link FailureCategory#SSRF_REVALIDATION} — é proteção/configuração
 * interna, NUNCA "alvo fora do ar".</p>
 */
public interface MonitorTargetValidator {

    /**
     * Valida o alvo de uma checagem.
     *
     * @param url URL do monitor (sem alteração em relação à persistida)
     * @throws RuntimeException (produção: {@code ApiException}) quando o alvo
     *                          não é permitido — nunca deve ser tratada como
     *                          falha do alvo
     */
    void validate(String url);
}
