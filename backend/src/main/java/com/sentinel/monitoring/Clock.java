package com.sentinel.monitoring;

import java.time.Instant;

/**
 * Relógio injetável do motor de monitoramento (Prompt 09).
 *
 * <p><b>Por que uma interface:</b> todo o cálculo de vencimento
 * ({@link DueTracker}) e de duração ({@link HttpMonitorExecutor}) depende de
 * "agora". Com um relógio real, testar "intervalo de 60s respeitado" exigiria
 * sleeps de minutos (lentos e flaky). A implementação de produção é
 * {@link SystemClock}; os testes injetam um relógio controlável e avançam o
 * tempo de forma determinística — o mesmo padrão da {@code DnsResolver}
 * injetável do Prompt 08.</p>
 *
 * <p><b>Não confundir com {@code java.time.Clock}:</b> esta é a interface do
 * domínio do Sentinel (do pacote {@code com.sentinel.monitoring}); ela não
 * estende nem substitui a do JDK. O JDK só entra como implementação, se um
 * dia for útil.</p>
 */
public interface Clock {

    /** @return o instante atual segundo este relógio. */
    Instant instant();
}
