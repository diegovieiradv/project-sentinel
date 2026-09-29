package com.sentinel.monitoring;

import java.io.IOException;

import java.net.http.HttpRequest;

/**
 * Fronteira de rede do motor de monitoramento (Prompt 09).
 *
 * <p><b>Por que uma interface:</b> o {@link HttpMonitorExecutor} precisa ser
 * testado sem Internet e sem tocar em localhost real (o {@code SsrfGuard}
 * bloqueia loopback, por isso um transporte real só entra em teste com um
 * validador de teste injetado). Os testes de executor usam um servidor local
 * do JDK por trás desta interface; os testes de SSRF usam um transporte de
 * gravação que <b>não conecta em lugar nenhum</b> — assim o guard real é
 * exercitado sem enfraquecer a política de produção.</p>
 *
 * <p><b>Contrato:</b> envia a requisição já montada (método, timeout,
 * headers) e devolve <b>apenas o status HTTP</b>. O corpo é descartado no
 * caminho de produção ({@code BodyHandlers.discarding()}) — quem implementa
 * NUNCA deve retê-lo em memória.</p>
 *
 * @see JdkHttpTransport
 */
public interface HttpTransport {

    /**
     * Executa a requisição e devolve o status HTTP recebido.
     *
     * @param request requisição já construída (com timeout do monitor)
     * @return status HTTP da resposta (qualquer código — 3xx não é seguido)
     * @throws IOException            falha de conexão/DNS/TLS/timeout/IO
     * @throws InterruptedException   interrupção da thread de execução
     */
    int send(HttpRequest request) throws IOException, InterruptedException;
}
