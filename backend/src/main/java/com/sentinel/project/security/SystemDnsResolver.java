package com.sentinel.project.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Resolução de DNS real (padrão de produção) — delega a
 * {@link InetAddress#getAllByName(String)}, que devolve todos os registros A/AAAA
 * do nome.
 *
 * <p>É o único componente deste pacote que pode tocar a rede. Nos testes de
 * integração ele é sobreposto por um stub injetável ({@link DnsResolver}),
 * então a suíte não depende de Internet nem do resolver do ambiente.</p>
 *
 * <p>{@code InetAddress.getAllByName} <b>não</b> resolve literais IP (v4/v6
 * são interpretados localmente) — no caminho de literais o guard nem chega a
 * chamar este componente.</p>
 */
@Component
public class SystemDnsResolver implements DnsResolver {

    @Override
    public List<InetAddress> resolve(String hostname) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(hostname));
    }
}
