package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sentinel.error.ApiException;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Classificação de resultados do {@link HttpMonitorExecutor} (Prompt 09)
 * contra um servidor HTTP real do JDK em loopback.
 *
 * <p><b>Por que servidor real:</b> é a única forma honesta de provar a
 * troca de rede do executor — método, {@code User-Agent}, timeout por
 * request, redirecionamento NÃO seguido e leitura de status. O servidor
 * fica em {@code 127.0.0.1} e por isso ESTES testes usam um
 * {@link MonitorTargetValidator} poroso (lambda) — o guard real de
 * produção bloqueia loopback, como deve, e é exercitado à parte no
 * {@link HttpMonitorExecutorSsrfRuntimeTest}.</p>
 *
 * <p><b>Corpo nunca vaza:</b> o handler devolve um marcador e o teste
 * prova que nenhuma mensagem de resultado o contém (o transporte de
 * produção usa {@code BodyHandlers.discarding()}).</p>
 */
class HttpMonitorExecutorTest {

    /** Marcador que NUNCA pode aparecer em log/mensagem/resultado. */
    private static final String BODY_MARKER = "SENTINEL_BODY_MUST_NOT_ESCAPE_9f3a";

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private static final List<String> REQUEST_LOG = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger HOP_HITS = new AtomicInteger();

    private static HttpServer server;
    private static String baseUrl;

    private final JdkHttpTransport realTransport = new JdkHttpTransport();

    /** Requisições efetivamente enviadas (via transporte real ou espione). */
    private final List<HttpRequest> captured = Collections.synchronizedList(new ArrayList<>());

    /**
     * Caminho "honesto": rede REAL do JDK + captura do que foi enviado — é o
     * que os testes de método/timeout/redirecionamento usam.
     */
    private final HttpTransport capturingTransport = request -> {
        captured.add(request);
        return realTransport.send(request);
    };

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // executor dedicado: o handler de /slow dorme e não pode bloquear os demais
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/ok", exchange -> respond(exchange, 200, BODY_MARKER));
        server.createContext("/error", exchange -> respond(exchange, 503, "upstream exploded"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "no such thing"));
        server.createContext("/hop", exchange -> {
            HOP_HITS.incrementAndGet();
            respond(exchange, 200, "redirected");
        });
        server.createContext("/redirect", exchange -> {
            log(exchange);
            exchange.getResponseHeaders().add("Location", "/hop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
                respond(exchange, 200, "late");
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.createContext("/probe", exchange -> respond(exchange, 200, "probed"));
        server.start();
        baseUrl =
                "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetState() {
        REQUEST_LOG.clear();
        HOP_HITS.set(0);
        captured.clear();
    }

    // --- sucesso ------------------------------------------------------------

    @Test
    void successfulCheckCarriesStatusAndNeverTheBody() {
        Monitor monitor = monitor(baseUrl + "/ok");
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        MonitorExecutionResult result = executor.execute(monitor);

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.httpStatus()).contains(200);
        assertThat(result.failureCategory()).isEmpty();
        assertThat(result.message()).contains("Completed");
        // o corpo do alvo não aparece em lugar nenhum do resultado
        assertThat(result.message()).doesNotContain(BODY_MARKER);
    }

    @Test
    void serverErrorIsTargetFailureWithHttpStatusCategory() {
        Monitor monitor = monitor(baseUrl + "/error");
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        MonitorExecutionResult result = executor.execute(monitor);

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.HTTP_ERROR);
        assertThat(result.httpStatus()).contains(503);
        assertThat(result.message()).isEqualTo("Target responded with HTTP 503");
    }

    @Test
    void notFoundIsTargetFailureWithHttpStatusCategory() {
        Monitor monitor = monitor(baseUrl + "/missing");
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        MonitorExecutionResult result = executor.execute(monitor);

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.HTTP_ERROR);
        assertThat(result.httpStatus()).contains(404);
    }

    // --- requisição ---------------------------------------------------------

    @Test
    void getAndHeadMethodsAreSentWithIdentifiableUserAgentAndMonitorTimeout() {
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        executor.execute(monitor(baseUrl + "/probe"));
        executor.execute(MonitorFixtures.monitor(
                UUID.randomUUID(), baseUrl + "/probe", MonitorMethod.HEAD, 60, 4321));

        assertThat(REQUEST_LOG).hasSize(2);
        assertThat(REQUEST_LOG.get(0)).startsWith("GET /probe");
        assertThat(REQUEST_LOG.get(1)).startsWith("HEAD /probe");
        assertThat(REQUEST_LOG.get(0)).contains("Sentinel-Monitor/1.0");
        assertThat(REQUEST_LOG.get(1)).contains("Sentinel-Monitor/1.0");

        // o timeout por checagem é o timeoutMillis do monitor (não um global)
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).timeout()).contains(Duration.ofMillis(5000));
        assertThat(captured.get(1).timeout()).contains(Duration.ofMillis(4321));
    }

    @Test
    void redirectIsNeverFollowed() {
        Monitor monitor = monitor(baseUrl + "/redirect");
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        MonitorExecutionResult result = executor.execute(monitor);

        // o 302 é devolvido como RESPOSTA (classificada), nunca perseguido
        assertThat(result.httpStatus()).contains(302);
        assertThat(HOP_HITS.get())
                .as("Redirect.NEVER — o alvo do Location nunca foi tocado")
                .isZero();
    }

    // --- falhas de rede -----------------------------------------------------

    @Test
    void timeoutIsClassifiedAsTimeoutCategory() {
        Monitor slow = MonitorFixtures.monitor(
                UUID.randomUUID(), baseUrl + "/slow", MonitorMethod.GET, 60, 200);
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        long startedAt = System.nanoTime();
        MonitorExecutionResult result = executor.execute(slow);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.TIMEOUT);
        assertThat(result.httpStatus()).isEmpty();
        assertThat(result.message()).isEqualTo("Request exceeded the monitor timeout");
        // o limite do monitor foi respeitado (200ms, bem abaixo dos 1500 do servidor)
        assertThat(elapsedMs).isLessThan(1400);
    }

    @Test
    void connectionRefusedIsClassifiedAsConnect() throws IOException {
        int closedPort = freePort();
        Monitor monitor = monitor("http://127.0.0.1:" + closedPort + "/probe");
        HttpMonitorExecutor executor = executor(acceptAll(), capturingTransport);

        MonitorExecutionResult result = executor.execute(monitor);

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.CONNECT);
        assertThat(result.httpStatus()).isEmpty();
        assertThat(result.message()).isEqualTo("Could not connect to target");
    }

    @Test
    void dnsFailureIsClassifiedAsDns() {
        HttpTransport failingDns = request -> {
            throw new UnknownHostException("db.internal.example");
        };
        Monitor monitor = monitor("https://db.internal.example/health");
        HttpMonitorExecutor executor = executor(acceptAll(), failingDns);

        MonitorExecutionResult result = executor.execute(monitor);

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.DNS);
        assertThat(result.message()).isEqualTo("Unable to resolve target host");
    }

    @Test
    void tlsHandshakeFailureIsClassifiedAsTls() {
        HttpTransport failingTls = request -> {
            throw new SSLException("handshake failure");
        };
        HttpMonitorExecutor executor = executor(acceptAll(), failingTls);

        MonitorExecutionResult result = executor.execute(monitor("https://93.184.216.34/health"));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.TLS);
        assertThat(result.message()).isEqualTo("TLS handshake with target failed");
    }

    @Test
    void ioFailureDuringExchangeIsClassifiedAsConnect() {
        HttpTransport brokenPipe = request -> {
            throw new IOException("connection reset by peer");
        };
        HttpMonitorExecutor executor = executor(acceptAll(), brokenPipe);

        MonitorExecutionResult result = executor.execute(monitor("https://93.184.216.34/health"));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.TARGET_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.CONNECT);
    }

    // --- guard e bugs internos ----------------------------------------------

    @Test
    void ssrfRejectionIsInternalAndNeverReachesTheNetwork() {
        HttpTransport spy = request -> {
            captured.add(request);
            return 200;
        };
        MonitorTargetValidator guard = url -> {
            throw new ApiException(
                    "MONITOR_TARGET_NOT_ALLOWED",
                    "This target is not allowed for security reasons.",
                    HttpStatus.BAD_REQUEST);
        };
        HttpMonitorExecutor executor = executor(guard, spy);

        MonitorExecutionResult result =
                executor.execute(monitor("http://169.254.169.254/latest/meta-data/"));

        // proteção funcionando NÃO é "alvo fora do ar"
        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SENTINEL_INTERNAL_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.SSRF_REVALIDATION);
        assertThat(captured)
                .as("a revalidação barra ANTES de qualquer conexão")
                .isEmpty();
    }

    @Test
    void unexpectedRuntimeExceptionIsClassifiedAsInternal() {
        HttpTransport buggy = request -> {
            throw new IllegalStateException("transport bug");
        };
        HttpMonitorExecutor executor = executor(acceptAll(), buggy);

        MonitorExecutionResult result = executor.execute(monitor("https://93.184.216.34/health"));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SENTINEL_INTERNAL_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.INTERNAL);
        assertThat(result.message()).isEqualTo("Unexpected error during monitor execution");
    }

    @Test
    void interruptionIsInternalAndRestoresTheInterruptFlag() {
        HttpTransport interrupted = request -> {
            throw new InterruptedException("pool shutting down");
        };
        HttpMonitorExecutor executor = executor(acceptAll(), interrupted);

        MonitorExecutionResult result = executor.execute(monitor("https://93.184.216.34/health"));

        assertThat(result.outcome()).isEqualTo(MonitorOutcome.SENTINEL_INTERNAL_FAILURE);
        assertThat(result.failureCategory()).contains(FailureCategory.INTERNAL);
        // o sinal é restaurado — e limpo aqui para não vazar para os demais testes
        assertThat(Thread.interrupted())
                .as("interrupt flag must be restored")
                .isTrue();
    }

    // --- apoio --------------------------------------------------------------

    private static Monitor monitor(String url) {
        return MonitorFixtures.monitor(UUID.randomUUID(), url);
    }

    private static HttpMonitorExecutor executor(
            MonitorTargetValidator validator, HttpTransport transport) {
        return new HttpMonitorExecutor(validator, transport, () -> T0);
    }

    private static MonitorTargetValidator acceptAll() {
        return url -> { };
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void log(HttpExchange exchange) {
        REQUEST_LOG.add(exchange.getRequestMethod()
                + " "
                + exchange.getRequestURI().getPath()
                + " "
                + exchange.getRequestHeaders().getFirst("User-Agent"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        log(exchange);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
