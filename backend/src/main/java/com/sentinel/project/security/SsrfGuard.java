package com.sentinel.project.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.sentinel.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Guarda SSRF: valida que a URL configurada num monitor aponta para um alvo
 * PÚBLICO antes de persistir (Prompt 08).
 *
 * <p><b>Ordem das checagens</b> (todas violações respondem
 * {@code 400 MONITOR_TARGET_NOT_ALLOWED} — nunca 500, nunca detalhe interno):</p>
 * <ol>
 *   <li>parse com {@link URI} (nada de regex própria): sintaxe inválida,
 *       vazia ou {@code null} rejeita;</li>
 *   <li>scheme só {@code http}/{@code https} ({@code file:}, {@code ftp:},
 *       relativa, etc.);</li>
 *   <li>host presente;</li>
 *   <li>sem {@code userinfo} ({@code user:pass@host} — credencial na URL não
 *       pertence a um monitor);</li>
 *   <li>sem {@code fragment} (nunca viaja para o servidor, mas abre margem a
 *       confusão de parsing);</li>
 *   <li>porta ausente ou em 1..65535 (0 e fora da faixa rejeitam);</li>
 *   <li>fast-path {@code localhost}/{@code *.localhost} bloqueado <b>antes</b>
 *       de qualquer resolução;</li>
 *   <li>literal IP classifica <b>direto</b> (sem DNS); hostname resolve via
 *       {@link DnsResolver} e <b>TODOS</b> os endereços precisam ser públicos
 *       (mixed público+privado é bloqueado).</li>
 * </ol>
 *
 * <p><b>Mensagens do contrato público:</b> violação de regra → "This target
 * is not allowed for security reasons."; falha de DNS → "Unable to resolve
 * target host". Nenhuma das duas revela IPs, ranges ou a regra exata — o
 * cliente aprende que o alvo não é permitido, não como defender o servidor.</p>
 *
 * <p><b>Classes bloqueadas</b> (classificação EXPLÍCITA, não só
 * {@code isSiteLocalAddress}):</p>
 * <ul>
 *   <li>IPv4: 0/8, 10/8, 100.64/10 (CGNAT), 127/8, 169.254/16, 172.16/12,
 *       192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16, 198.18/15,
 *       198.51.100/24, 203.0.113/24, 224/4 (multicast) e 240/4 (reservado,
 *       inclusive 255.255.255.255);</li>
 *   <li>IPv6: {@code ::}, {@code ::1}, ULA {@code fc00::/7}, link-local
 *       {@code fe80::/10}, multicast {@code ff00::/8}, tudo fora de
 *       {@code 2000::/3} e a faixa de documentação {@code 2001:db8::/32}
 *       (ex.: {@code 2001:4860:4860::8888} é aceita).</li>
 * </ul>
 *
 * <p><b>Escopo e limites declarados</b> (também no
 * {@code docs/monitors.md}): este guard valida o <b>cadastro</b>. Ele reduz o
 * espaço de ataque, mas não é uma garantia em tempo de execução — DNS
 * rebinding e redirects para alvos internos exigem revalidação
 * <b>ANTES de cada conexão e de cada redirect</b> da checagem em execução,
 * trabalho do Prompt 09. Por isso a interface {@link DnsResolver} é
 * injetável: o mesmo guard é reutilizável lá sem repetir a política de
 * ranges.</p>
 */
@Component
public class SsrfGuard {

    /** Código estável do contrato público da API (ver docs/monitors.md). */
    public static final String ERROR_CODE = "MONITOR_TARGET_NOT_ALLOWED";

    /** Mensagem de violação de política — sem citar regra, IP ou range. */
    public static final String NOT_ALLOWED_MESSAGE =
            "This target is not allowed for security reasons.";

    /** Mensagem de falha de resolução — sem citar servidor DNS ou detalhe. */
    public static final String UNRESOLVED_MESSAGE = "Unable to resolve target host";

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    private final DnsResolver dnsResolver;

    public SsrfGuard(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    /**
     * Valida o alvo de um monitor.
     *
     * @param rawUrl URL crua vinda do payload (pode ser {@code null}/em
     *               branco — ausência de alvo também é alvo não permitido)
     * @throws ApiException {@code 400 MONITOR_TARGET_NOT_ALLOWED} em qualquer
     *                      violação ou falha de resolução
     */
    public void validate(String rawUrl) {
        URI uri = parse(rawUrl);

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw notAllowed();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw notAllowed();
        }

        if (uri.getRawUserInfo() != null) {
            throw notAllowed();
        }
        if (uri.getRawFragment() != null) {
            throw notAllowed();
        }

        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw notAllowed();
        }

        assertPublicHost(host.trim());
    }

    // --- alvo ---------------------------------------------------------------

    private static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw notAllowed();
        }
        try {
            return new URI(rawUrl);
        }
        catch (URISyntaxException ex) {
            throw notAllowed();
        }
    }

    /**
     * Fast-path de nomes locais ANTES de resolver: {@code localhost} e
     * {@code *.localhost} nunca viram endereço público (e um stub/DNS
     * enganoso não muda isso). O ponto final de FQDN ({@code localhost.}) é
     * removido antes da comparação — mesmo nome, mesma regra.
     */
    private void assertPublicHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw notAllowed();
        }

        InetAddress literal = parseLiteral(host);
        if (literal != null) {
            if (!isPublic(literal)) {
                throw notAllowed();
            }
            return;
        }

        List<InetAddress> resolved;
        try {
            resolved = dnsResolver.resolve(host);
        }
        catch (UnknownHostException ex) {
            throw unresolved();
        }
        if (resolved == null || resolved.isEmpty()) {
            throw unresolved();
        }
        for (InetAddress address : resolved) {
            if (address == null || !isPublic(address)) {
                // UM endereço privado basta: um hostname com registro público
                // + privado é o caso clássico de bypass por round-robin.
                throw notAllowed();
            }
        }
    }

    /**
     * Interpreta o host como literal IP <b>sem tocar a rede</b>: devolve o
     * endereço ou {@code null} quando o host é um nome (então o caminho é o
     * {@link DnsResolver}).
     */
    private static InetAddress parseLiteral(String host) {
        String value = host;
        if (value.length() > 1 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }

        if (value.indexOf(':') >= 0) {
            // só um literal IPv6 contém ':' após o parse do URI — a checagem
            // de forma abaixo impede qualquer chamada de DNS com um host
            // malformado entre colchetes
            if (!looksLikeIpv6(value)) {
                throw notAllowed();
            }
            try {
                // sem '[' e com ':' o JDK classifica como literal (sem DNS)
                return InetAddress.getByName(value);
            }
            catch (UnknownHostException ex) {
                throw notAllowed();
            }
        }

        byte[] octets = parseIpv4(value);
        if (octets == null) {
            return null; // nome legítimo → resolver
        }
        try {
            return InetAddress.getByAddress(octets); // só bytes, sem DNS
        }
        catch (UnknownHostException ex) {
            throw notAllowed();
        }
    }

    /** Heurística barata: hex + ':' (+ cauda IPv4 opcional e zona '%'). */
    private static boolean looksLikeIpv6(String value) {
        int zone = value.indexOf('%');
        String address = zone >= 0 ? value.substring(0, zone) : value;
        int colons = 0;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c == ':') {
                colons++;
            }
            else if (c != '.' && Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return colons >= 2;
    }

    /** {@code a.b.c.d} com 4 octets válidos, ou {@code null} se não for. */
    private static byte[] parseIpv4(String value) {
        if (value.isEmpty() || value.length() > 15) {
            return null; // literal v4 no máximo "255.255.255.255"
        }
        int dots = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                dots++;
            }
            else if (c < '0' || c > '9') {
                return null; // letras → hostname
            }
        }
        if (dots != 3) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        byte[] octets = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            octets[i] = (byte) octet;
        }
        return octets;
    }

    // --- classificação ------------------------------------------------------

    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
        }
        return isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int o0 = bytes[0] & 0xff;
        int o1 = bytes[1] & 0xff;
        int o2 = bytes[2] & 0xff;

        if (o0 == 0) {
            return false; // 0.0.0.0/8
        }
        if (o0 == 10) {
            return false; // 10.0.0.0/8
        }
        if (o0 == 100 && o1 >= 64 && o1 <= 127) {
            return false; // 100.64.0.0/10 (CGNAT)
        }
        if (o0 == 127) {
            return false; // 127.0.0.0/8 (loopback)
        }
        if (o0 == 169 && o1 == 254) {
            return false; // 169.254.0.0/16 (link-local, incl. metadata cloud)
        }
        if (o0 == 172 && o1 >= 16 && o1 <= 31) {
            return false; // 172.16.0.0/12
        }
        if (o0 == 192 && o1 == 0 && o2 == 0) {
            return false; // 192.0.0.0/24 (IETF protocol assignments)
        }
        if (o0 == 192 && o1 == 0 && o2 == 2) {
            return false; // 192.0.2.0/24 (TEST-NET-1)
        }
        if (o0 == 192 && o1 == 88 && o2 == 99) {
            return false; // 192.88.99.0/24 (6to4 relay anycast)
        }
        if (o0 == 192 && o1 == 168) {
            return false; // 192.168.0.0/16
        }
        if (o0 == 198 && (o1 == 18 || o1 == 19)) {
            return false; // 198.18.0.0/15 (benchmark)
        }
        if (o0 == 198 && o1 == 51 && o2 == 100) {
            return false; // 198.51.100.0/24 (TEST-NET-2)
        }
        if (o0 == 203 && o1 == 0 && o2 == 113) {
            return false; // 203.0.113.0/24 (TEST-NET-3)
        }
        return o0 < 224; // 224.0.0.0/4 multicast e 240.0.0.0/4 reservado
    }

    /**
     * Classificação IPv6 explícita — NUNCA confiar só em
     * {@code isSiteLocalAddress()}/{@code isLoopbackAddress()}: eles não
     * cobrem ULA, documentação, multicast nem o resto de 2000::/3.
     *
     * <p><b>Comparações SEMPRE sem sinal.</b> {@code byte} é assinado, então
     * {@code bytes[3] == 0xb8} comparia {@code -72} com {@code 184} e nunca
     * casava — a faixa de documentação passava/descia silenciosamente. Todo
     * octeto é lido como {@code (b & 0xff)} (ver {@link #unsigned}).</p>
     */
    private static boolean isPublicIpv6(byte[] bytes) {
        if (isAllZero(bytes)) {
            return false; // ::
        }
        if (unsigned(bytes[15]) == 1 && isAllZero(Arrays.copyOf(bytes, 15))) {
            return false; // ::1 (loopback)
        }
        int b0 = unsigned(bytes[0]);
        int b1 = unsigned(bytes[1]);
        int b2 = unsigned(bytes[2]);
        int b3 = unsigned(bytes[3]);
        if ((b0 & 0xfe) == 0xfc) {
            return false; // fc00::/7 (ULA)
        }
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
            return false; // fe80::/10 (link-local)
        }
        if (b0 == 0xff) {
            return false; // ff00::/8 (multicast)
        }
        if ((b0 & 0xe0) != 0x20) {
            return false; // fora de 2000::/3 (global unicast)
        }
        // 2001:db8::/32 — faixa de documentação, dentro de 2000::/3
        return !(b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8);
    }

    /** Octeto de endereço como inteiro 0..255 (byte é assinado: 0xb8 = -72). */
    private static int unsigned(byte octet) {
        return octet & 0xff;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // --- erros --------------------------------------------------------------

    private static ApiException notAllowed() {
        return new ApiException(ERROR_CODE, NOT_ALLOWED_MESSAGE, HttpStatus.BAD_REQUEST);
    }

    private static ApiException unresolved() {
        return new ApiException(ERROR_CODE, UNRESOLVED_MESSAGE, HttpStatus.BAD_REQUEST);
    }
}
