package com.sentinel.project.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.error.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Evidência B1 (Hardening — Prompt 17): formas <b>não-canônicas</b> de IPv4
 * (zeros à esquerda, octetos encurtados, decimal, hex, fora de faixa) e o risco
 * de <b>diferencial de parser</b> entre a guarda SSRF e o transporte HTTP.
 *
 * <p><b>Cadeia de decisões verificada aqui</b> (três portas, todas fechadas):</p>
 * <ol>
 *   <li>{@code java.URI.getHost()} — o MESMO parser que o
 *       {@code java.net.http.HttpClient} usa. Hosts "parecidos com IPv4 mas
 *       inválidos" ({@code 127.1}, {@code 999.1.1.1}, {@code 1.2.3.4.5},
 *       {@code 0x7f.0.0.1}, {@code +127.0.0.1}, {@code 127.0.0.1.}) já nascem
 *       com {@code host == null} → a guarda rejeita ANTES de resolver nada, e
 *       o transporte falharia do mesmo jeito: sem diferencial;</li>
 *   <li>{@code SsrfGuard.parseIpv4} — só um host com EXATAMENTE 4 octetos de
 *       até 3 dígitos é classificado localmente (decimal, nunca octal);
 *       literais não-públicos são bloqueados <b>sem</b> consulta de DNS;</li>
 *   <li>Demais formas numéricas (octeto com 4 dígitos, só-números, etc.) caem
 *       no caminho DNS, cujo classificador é o <b>mesmo</b>
 *       {@link java.net.InetAddress} do {@link SystemDnsResolver} e do
 *       transporte: resolvida para endereço não-público → bloqueada;
 *       irresolvível → bloqueada com a mensagem de resolução.</li>
 * </ol>
 *
 * <p><b>Não-finding documentado:</b> {@code 0177.0.0.1} (4 dígitos) passa pela
 * guarda AO SER RESOLVIDO e o JDK lê {@code 177.0.0.1} — decimal, igual ao
 * {@code Integer.parseInt} da própria guarda. Em octal seria 127.0.0.1
 * (loopback), mas nenhum dos dois lados usa octal, então alvo aceito e alvo
 * conectado são o MESMO endereço público.</p>
 *
 * <p>Sem Spring/contêiner: a guarda é construída direto com um stub de
 * {@link DnsResolver} que <b>registra</b> cada consulta (prova do caminho
 * percorrido) e falha por padrão — nenhum teste depende de DNS do ambiente.
 * O único grupo que delega ao {@link java.net.InetAddress} usa formas que o
 * JDK resolve localmente (o próprio endereço devolvido — 0.0.0.0/127.0.0.1 —
 * só existe como leitura literal, nunca vindo de rede).</p>
 */
class SsrfGuardNonCanonicalTargetTest {

    /** Hosts consultados no DNS — prova de que o caminho percorrido é este. */
    private final List<String> dnsQueries = new ArrayList<>();

    private final SsrfGuard guard = new SsrfGuard(this::resolveStub);

    /** DNS indisponível: registra a tentativa e falha (como um resolver fora do ar). */
    private List<InetAddress> resolveStub(String hostname) throws UnknownHostException {
        dnsQueries.add(hostname);
        throw new UnknownHostException(hostname);
    }

    // --- 1. porta do URI: host inválido já nasce rejeitado ------------------

    @Test
    void hostThatUriRefusesIsBlockedBeforeAnyDnsLookup() {
        // "Parecido com IPv4 mas inválido": o java.URI devolve host nulo
        // (e o HttpClient rejeitaria o MESMO URI) — a guarda nem chega a olhar
        // endereço. Sem consulta de DNS, sem diferencial de parser.
        assertBlocked("http://127.1/"); // octeto encurtado
        assertBlocked("http://127.0.1/"); // dois octetos encurtados
        assertBlocked("http://127.000.1/"); // três octetos
        assertBlocked("http://999.999.999.999/"); // fora de faixa
        assertBlocked("http://1.2.3.4.5/"); // cinco octetos
        assertBlocked("http://0x7f.0.0.1/"); // hex
        assertBlocked("http://127.0.0.1./"); // ponto final
        assertBlocked("http://+127.0.0.1/"); // caractere ilegal em host

        assertThat(dnsQueries)
                .describedAs("host rejeitado pelo URI → nenhuma resolução")
                .isEmpty();
    }

    // --- 2. porta do literal: classificação local, sem rede -----------------

    @Test
    void nonCanonicalLiteralIpIsClassifiedLocallyAndBlockedWhenNotPublic() {
        // Zeros à esquerda com 4 octetos: o URI aceita (<=255 por octeto) e a
        // guarda lê como DECIMAL (Integer.parseInt("010") = 10, nunca octal).
        assertBlocked("http://127.000.000.001/"); // 127.0.0.1 (loopback)
        assertBlocked("http://127.0.0.001/");
        assertBlocked("http://010.0.0.1/"); // 10.0.0.1 (10/8 privado)
        assertBlocked("http://000.0.0.1/"); // 0.0.0.1 (0/8 reservado)

        assertThat(dnsQueries)
                .describedAs("literais classificados sem nenhuma consulta de DNS")
                .isEmpty();
    }

    @Test
    void ipv4MappedIpv6LoopbackIsBlockedAsLiteralWithoutDns() {
        // A mesma classe IPv4 (127.0.0.1) escrita em IPv6-mapped hex.
        assertBlocked("http://[::ffff:7f00:1]/");
        assertBlocked("http://[::ffff:127.0.0.1]/");
        assertBlocked("http://[0:0:0:0:0:ffff:127.0.0.1]/");

        assertThat(dnsQueries).isEmpty();
    }

    // --- 3. porta do DNS: falha fechada com o stub --------------------------

    @Test
    void numericFormsBeyondFourOctetsFallThroughToDnsAndFailClosed() {
        // Uri aceita (é numérico e cada octeto <= 255), mas a guarda NÃO sabe
        // classificar localmente: parseIpv4 exige octetos de até 3 dígitos.
        // Caminho DNS → stub falha → bloqueado com a mensagem de resolução.
        assertUnresolved("http://0177.0.0.1/"); // 4 dígitos no primeiro octeto
        assertUnresolved("http://0000.1.0.1/");
        assertUnresolved("http://0000.0.0.1/");
        assertUnresolved("http://2130706433/"); // decimal de 127.0.0.1
        assertUnresolved("http://0/");

        assertThat(dnsQueries)
                .describedAs("cada forma foi ao DNS — nunca aceita como literal")
                .containsExactly(
                        "0177.0.0.1", "0000.1.0.1", "0000.0.0.1",
                        "2130706433", "0");
    }

    // --- 4. diferencial de parser: mesmo classificador do transporte --------

    @Test
    void numericNonCanonicalHostsResolveToNonPublicAddressesWithJdkParser()
            throws UnknownHostException {
        // Mesma resolução de produção (SystemDnsResolver →
        // InetAddress.getAllByName) e do java.net.http.HttpClient. Todas estas
        // formas o JDK lê LOCALMENTE (devolve 0.0.0.0/127.0.0.1/0.1.0.1 —
        // valores que só existem como leitura literal), então sem rede.
        SsrfGuard guardWithJdkDns = new SsrfGuard(hostname ->
                List.of(InetAddress.getAllByName(hostname)));

        List<String> hosts = List.of(
                "0", "2130706433", "0000.1.0.1", "0000.0.0.1");

        for (String host : hosts) {
            String url = "http://" + host + "/";
            assertThatThrownBy(() -> guardWithJdkDns.validate(url))
                    .describedAs("alvo não-público: %s", url)
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.getErrorCode()).isEqualTo(SsrfGuard.ERROR_CODE);
                        assertThat(api.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessage())
                                .isEqualTo(SsrfGuard.NOT_ALLOWED_MESSAGE);
                    });
        }
    }

    @Test
    void acceptedNonCanonicalFormIsReadAsDecimalByGuardAndTransport()
            throws UnknownHostException {
        // 0177.0.0.1 (4 dígitos) só passa pelo caminho DNS, cujo classificador
        // é o próprio JDK — o MESMO que o transporte usa. Em octal seria
        // 127.0.0.1 (loopback); os dois lados leem decimal → mesmo endereço.
        InetAddress viaNonCanonical = InetAddress.getByName("0177.0.0.1");
        InetAddress viaCanonical = InetAddress.getByName("177.0.0.1");
        assertThat(viaNonCanonical).isEqualTo(viaCanonical);
        assertThat(viaNonCanonical.getHostAddress()).isEqualTo("177.0.0.1");

        SsrfGuard guardWithJdkDns = new SsrfGuard(hostname ->
                List.of(InetAddress.getAllByName(hostname)));
        assertThatCode(() -> guardWithJdkDns.validate("http://0177.0.0.1/"))
                .describedAs("endereço público decimal → aceito, sem diferencial")
                .doesNotThrowAnyException();
    }

    // --- contrato -----------------------------------------------------------

    private void assertBlocked(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .describedAs("bloqueado: %s", url)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(SsrfGuard.ERROR_CODE);
                    assertThat(api.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessage()).isEqualTo(SsrfGuard.NOT_ALLOWED_MESSAGE);
                });
    }

    private void assertUnresolved(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .describedAs("irresolvível: %s", url)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(SsrfGuard.ERROR_CODE);
                    assertThat(api.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessage()).isEqualTo(SsrfGuard.UNRESOLVED_MESSAGE);
                });
    }
}
