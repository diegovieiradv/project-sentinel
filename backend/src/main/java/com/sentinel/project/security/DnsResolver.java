package com.sentinel.project.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolução de nomes usada pela validação SSRF — a ÚNICA fronteira em que o
 * cadastro de monitor fala com o mundo externo.
 *
 * <p><b>Por que uma interface:</b> o {@code SsrfGuard} precisa classificar os
 * endereços que um hostname resolve, e isso não pode ser exercitado em teste
 * sem Internet (ou pior, dependendo do DNS do ambiente). A implementação de
 * produção é {@link SystemDnsResolver}; os testes injetam um stub que devolve
 * endereços fixos — host não mapeado lança {@link UnknownHostException},
 * exatamente como um DNS real falhando.</p>
 *
 * <p><b>Contrato:</b> devolve <b>TODOS</b> os endereços do nome (um hostname
 * pode ter vários) — o guard exige que todos sejam públicos; qualquer
 * endereço privado rejeita o alvo. Lançar {@link UnknownHostException} é
 * traduzido pelo guard em {@code MONITOR_TARGET_NOT_ALLOWED} com a mensagem
 * "Unable to resolve target host" (nunca um 500 e nunca detalhe interno).</p>
 *
 * <p><b>Escopo futuro (Prompt 09):</b> resolver NOVAmente antes de cada
 * conexão e cada redirect da checagem em execução — o guard deste prompt
 * valida o CADASTRO; a proteção contra DNS rebinding (TOCTOU entre validar e
 * conectar) pertence ao executor de checks.</p>
 */
public interface DnsResolver {

    /**
     * @param hostname nome do alvo (já extraído de uma URL com scheme/host
     *                 válidos)
     * @return todos os endereços do nome (nunca vazio quando não lança)
     * @throws UnknownHostException nome inexistente ou irresolvível
     */
    List<InetAddress> resolve(String hostname) throws UnknownHostException;
}
