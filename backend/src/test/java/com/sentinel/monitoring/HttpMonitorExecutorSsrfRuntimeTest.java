package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.security.DnsResolver;
import com.sentinel.project.security.SsrfGuard;

import org.junit.jupiter.api.Test;

/**
 * Revalidação SSRF em EXECUÇÃO (Prompt 09) — o {@link SsrfGuard} real do
 * Prompt 08 é quem decide, imediatamente antes de cada conexão.
 *
 * <p><b>Por que este teste existe separado do cadastro
 * ({@code MonitorSsrfTest}):</b> lá o guard barra <em>escrita</em> de URL;
 * aqui ele barra <em>conexão</em>. É o guard que cobre o TOCTOU/DNS
 * rebinding — o alvo pode ter sido público quando persistido e privado
 * agora (a resolução acontece NOVAMENTE nesta fronteira). O transporte é um
 * gravador que nunca conecta: se a revalidação falhar em barar, o teste
 * prova que a conexão teria sido tentada.</p>
 *
 * <p>Hostnames usam um {@link DnsResolver} stub (mesmo padrão do
 * {@code MonitorSsrfTest}) — sem Internet e sem flakiness.</p>
 */
class HttpMonitorExecutorSsrfRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private final StubDnsResolver dns = new StubDnsResolver();
    private final RecordingTransport transport = new RecordingTransport();
    private final HttpMonitorExecutor executor = new HttpMonitorExecutor(
            new SsrfGuardMonitorTargetValidator(new SsrfGuard(dns)),
            transport,
            () -> T0);

    // --- alvos internos barrados na execução --------------------------------

    @Test
    void loopbackTargetIsRejectedBeforeAnyConnection() {
        MonitorExecutionResult result = executor.execute(monitor("http://127.0.0.1/health"));

        assertRejectedByGuard(result);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void cloudMetadataTargetIsRejectedBeforeAnyConnection() {
        MonitorExecutionResult result =
                executor.execute(monitor("http://169.254.169.254/latest/meta-data/"));

        assertRejectedByGuard(result);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void privateRangeTargetIsRejectedBeforeAnyConnection() {
        MonitorExecutionResult result = executor.execute(monitor("http://10.0.0.5/health"));

        assertRejectedByGuard(result);
        assertThat(transport.requests).isEmpty();
    }

    // --- TOCTOU / DNS rebinding ---------------------------------------------

    @Test
    void hostnameRebindingToPrivateAddressIsRejectedAtExecutionTime() throws UnknownHostException {
        // o alvo era público quando persistido; AGORA resolve para privado
        dns.maps("drift.example", "10.0.0.5");

        MonitorExecutionResult result = executor.execute(monitor("http://drift.example/health"));

        assertRejectedByGuard(result);
        assertThat(transport.requests).isEmpty();
        assertThat(dns.attemptedHosts())
                .as("a revalidação resolve o host NOVAMENTE nesta fronteira")
                .containsExactly("drift.example");
    }

    @Test
    void unresolvableHostnameIsRejectedWithoutConnecting() {
        // stub sem mapeamento → UnknownHostException → mesmo contrato do guard
        MonitorExecutionResult result = executor.execute(monitor("http://gone.example/health"));

        assertRejectedByGuard(result);
        assertThat(transport.requests).isEmpty();
        assertThat(dns.attemptedHosts()).containsExactly("gone.example");
    }

    // --- alvos legítimos executam -------------------------------------------

    @Test
    void publicLiteralTargetIsExecuted() {
        MonitorExecutionResult result = executor.execute(monitor(MonitorFixtures.PUBLIC_URL));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SUCCESS);
        assertThat(result.httpStatus()).contains(200);
        assertThat(transport.requests).hasSize(1);
        // literal público não depende de DNS
        assertThat(dns.attemptedHosts()).isEmpty();
    }

    @Test
    void hostnameResolvingOnlyToPublicAddressesIsExecuted() throws UnknownHostException {
        dns.maps("ok.example", "93.184.216.34", "93.184.216.35");

        MonitorExecutionResult result = executor.execute(monitor("http://ok.example/health"));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SUCCESS);
        assertThat(transport.requests).hasSize(1);
    }

    // --- apoio --------------------------------------------------------------

    /**
     * A recusa da revalidação é
     * {@code SENTINEL_INTERNAL_FAILURE + SSRF_REVALIDATION} — proteção
     * funcionando, NUNCA "alvo fora do ar" ({@code TARGET_FAILURE}).
     */
    private static void assertRejectedByGuard(MonitorExecutionResult result) {
        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SENTINEL_INTERNAL_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.SSRF_REVALIDATION);
        assertThat(result.httpStatus()).isEmpty();
        assertThat(result.message()).isEqualTo("Target rejected by SSRF revalidation");
    }

    private static Monitor monitor(String url) {
        return MonitorFixtures.monitor(UUID.randomUUID(), url);
    }

    /** Gravador que nunca conecta — devolve 200 se chegar a ser chamado. */
    private static final class RecordingTransport implements HttpTransport {

        private final List<java.net.http.HttpRequest> requests =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public int send(java.net.http.HttpRequest request) {
            requests.add(request);
            return 200;
        }
    }

    /**
     * DNS stub: resolve apenas o que o teste mapear; qualquer outro nome
     * lança {@link UnknownHostException} — e registra cada tentativa (é
     * assim que o teste prova que a fronteira REresolve o host).
     */
    static final class StubDnsResolver implements DnsResolver {

        private final Map<String, List<InetAddress>> entries = new ConcurrentHashMap<>();
        private final List<String> attempts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<InetAddress> resolve(String hostname) throws UnknownHostException {
            attempts.add(hostname);
            List<InetAddress> addresses = entries.get(hostname);
            if (addresses == null) {
                throw new UnknownHostException(hostname);
            }
            return addresses;
        }

        List<String> attemptedHosts() {
            return List.copyOf(attempts);
        }

        void maps(String hostname, String... literals) throws UnknownHostException {
            InetAddress[] resolved = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                // literais: InetAddress não consulta DNS para interpretá-los
                resolved[i] = InetAddress.getByName(literals[i]);
            }
            entries.put(hostname, List.of(resolved));
        }
    }
}
