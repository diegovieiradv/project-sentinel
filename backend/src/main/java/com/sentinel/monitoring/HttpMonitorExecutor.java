package com.sentinel.monitoring;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.net.ssl.SSLException;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executa UMA checagem HTTP de um monitor (Prompt 09).
 *
 * <p><b>Ordem inegociável:</b></p>
 * <ol>
 *   <li>registra o início no {@link Clock};</li>
 *   <li><b>revalida o alvo no {@link MonitorTargetValidator} IMEDIATAMENTE
 *       antes de tocar a rede</b> — é a defesa contra DNS rebinding/TOCTOU
 *       (o guard do cadastro valeu no passado; aqui vale de novo agora).
 *       A janela residual entre esta validação e o resolve interno do
 *       {@code HttpClient} não é eliminável por completo — ver
 *       {@code docs/monitoring-engine.md};</li>
 *   <li>monta a requisição: método GET/HEAD, {@code timeout =
 *       Monitor.timeoutMillis} e apenas um {@code User-Agent} identificável
 *       do Sentinel;</li>
 *   <li>envia pelo {@link HttpTransport} e classifica o resultado.</li>
 * </ol>
 *
 * <p><b>Timeout = UM round-trip, não um por fase:</b>
 * {@code Monitor.timeoutMillis} é o orçamento temporal da <b>resposta</b>
 * desta checagem — ele cobre a troca inteira (resolução de nome, conexão,
 * handshake TLS e espera da resposta), medida a partir do {@code send()} até
 * a resposta chegar. O <b>estabelecimento de conexão</b> tem teto próprio,
 * fixo em 10 s ({@code connectTimeout} do {@link JdkHttpTransport}): quando
 * {@code timeoutMillis > 10000} (o campo aceita até 30000), uma conexão
 * pendurada é cortada aos 10 s, não ao orçamento do monitor. Não existe
 * timeout separado por fase além destes dois, nem um timeout global do
 * {@code HttpClient} configurado aqui. Quando o orçamento estoura, o JDK
 * levanta {@link HttpTimeoutException}, este executor traduz em
 * {@link MonitorOutcome#TARGET_FAILURE} + {@code FailureCategory.TIMEOUT}
 * com mensagem de domínio, e a checagem termina normalmente — sem abortar
 * thread nem deixar o monitor preso.</p>
 *
 * <p><b>Nunca lança para fora:</b> toda exceção durante a execução —
 * inclusive as inesperadas — é traduzida em {@link MonitorExecutionResult}.
 * A fronteira adicional no {@link MonitorExecutionTask} existe por defesa em
 * profundidade: se o executor violar o contrato, a thread do pool não morre
 * com isso.</p>
 *
 * <p><b>Classificação</b> (Prompt 09):</p>
 * <ul>
 *   <li>SSRF recusou o alvo → {@code SENTINEL_INTERNAL_FAILURE} +
 *       {@code SSRF_REVALIDATION} (proteção funcionando, não alvo
 *       "down");</li>
 *   <li>{@code IOException} durante a troca → {@code TARGET_FAILURE} com
 *       {@code DNS}/{@code CONNECT}/{@code TIMEOUT}/{@code TLS};</li>
 *   <li>resposta recebida com qualquer status → request <b>completou</b>:
 *       2xx/3xx = {@code SUCCESS}; 4xx/5xx = {@code TARGET_FAILURE} +
 *       {@code HTTP_ERROR} (refinamento de saúde é Prompt 10);</li>
 *   <li>erro inesperado → {@code SENTINEL_INTERNAL_FAILURE} + {@code INTERNAL}.</li>
 * </ul>
 *
 * <p><b>Corpo:</b> proibido — o transporte descarta e este executor sequer
 * tem acesso a ele. Nenhum log/resultado carrega conteúdo de resposta, URL
 * com query string ou texto vindo da rede.</p>
 */
@Component
public class HttpMonitorExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpMonitorExecutor.class);

    /** Identificação do cliente no alvo — sem tenant, sem credencial. */
    static final String USER_AGENT = "Sentinel-Monitor/1.0";

    static final String USER_AGENT_HEADER = "User-Agent";

    /** 2xx/3xx = requisição completou e o alvo respondeu dentro do esperado. */
    private static final int HTTP_SUCCESS_LOWER = 200;
    private static final int HTTP_SUCCESS_UPPER = 400;

    // Mensagens de domínio — constantes de propósito: nada aqui pode conter
    // URL, query string, corpo de resposta ou texto vindo da rede.
    static final String MESSAGE_TIMEOUT = "Request exceeded the monitor timeout";
    static final String MESSAGE_DNS = "Unable to resolve target host";
    static final String MESSAGE_CONNECT = "Could not connect to target";
    static final String MESSAGE_TLS = "TLS handshake with target failed";
    static final String MESSAGE_SSRF = "Target rejected by SSRF revalidation";
    static final String MESSAGE_INTERNAL = "Unexpected error during monitor execution";

    private final MonitorTargetValidator targetValidator;
    private final HttpTransport transport;
    private final Clock clock;

    public HttpMonitorExecutor(
            MonitorTargetValidator targetValidator, HttpTransport transport, Clock clock) {
        this.targetValidator = targetValidator;
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * Executa a checagem e devolve o resultado — não propaga exceção.
     *
     * @param monitor monitor vindo da fila de execução (nunca {@code null}
     *                no caminho produtivo; um {@code null} é erro de
     *                programação do chamador e vira resultado
     *                {@link MonitorOutcome#SENTINEL_INTERNAL_FAILURE})
     * @return resultado transitório (in-memory) da execução
     */
    public MonitorExecutionResult execute(Monitor monitor) {
        Instant startedAt = clock.instant();
        try {
            return doExecute(monitor, startedAt);
        }
        catch (RuntimeException ex) {
            // Fronteira: bug inesperado (URL impossível, monitor nulo, etc.)
            // vira resultado interno — nunca mata a thread do pool.
            UUID monitorId = monitor == null ? null : monitor.getId();
            log.debug("monitor execution hit unexpected error monitorId={}", monitorId, ex);
            return MonitorExecutionResult.of(
                    monitorId,
                    startedAt,
                    clock.instant(),
                    null,
                    MonitorOutcome.SENTINEL_INTERNAL_FAILURE,
                    FailureCategory.INTERNAL,
                    MESSAGE_INTERNAL);
        }
    }

    private MonitorExecutionResult doExecute(Monitor monitor, Instant startedAt) {
        UUID monitorId = monitor.getId();

        // (2) Revalidação SSRF imediatamente antes da conexão (TOCTOU).
        try {
            targetValidator.validate(monitor.getUrl());
        }
        catch (RuntimeException ex) {
            log.debug("monitor target rejected by ssrf revalidation monitorId={}", monitorId, ex);
            return MonitorExecutionResult.of(
                    monitorId,
                    startedAt,
                    clock.instant(),
                    null,
                    MonitorOutcome.SENTINEL_INTERNAL_FAILURE,
                    FailureCategory.SSRF_REVALIDATION,
                    MESSAGE_SSRF);
        }

        // (3)+(4) monta e envia.
        try {
            HttpRequest request = buildRequest(monitor);
            int status = transport.send(request);
            return classifyResponse(monitorId, startedAt, status);
        }
        catch (HttpTimeoutException | SocketTimeoutException ex) {
            return failure(
                    monitorId, startedAt, MonitorOutcome.TARGET_FAILURE, FailureCategory.TIMEOUT,
                    MESSAGE_TIMEOUT);
        }
        catch (UnknownHostException ex) {
            return failure(
                    monitorId, startedAt, MonitorOutcome.TARGET_FAILURE, FailureCategory.DNS,
                    MESSAGE_DNS);
        }
        catch (SSLException ex) {
            return failure(
                    monitorId, startedAt, MonitorOutcome.TARGET_FAILURE, FailureCategory.TLS,
                    MESSAGE_TLS);
        }
        catch (ConnectException | NoRouteToHostException ex) {
            return failure(
                    monitorId, startedAt, MonitorOutcome.TARGET_FAILURE, FailureCategory.CONNECT,
                    MESSAGE_CONNECT);
        }
        catch (IOException ex) {
            // Qualquer outra falha de E/S da troca (reset, fechamento
            // abrupto, erro de handshake…) é problema do caminho até o alvo.
            return failure(
                    monitorId, startedAt, MonitorOutcome.TARGET_FAILURE, FailureCategory.CONNECT,
                    MESSAGE_CONNECT);
        }
        catch (InterruptedException ex) {
            // Interrupção é do NOSSO lado (shutdown) — restaura o sinal e
            // classifica como interno; nunca como falha do alvo.
            Thread.currentThread().interrupt();
            return failure(
                    monitorId, startedAt, MonitorOutcome.SENTINEL_INTERNAL_FAILURE,
                    FailureCategory.INTERNAL, MESSAGE_INTERNAL);
        }
    }

    /**
     * Monta a requisição da checagem: sem cookies, sem Authorization, sem
     * headers de tenant — apenas o {@code User-Agent} do Sentinel. O
     * {@code timeout} da request é o {@code Monitor.timeoutMillis}: é o
     * limite efetivo do check (menor que o intervalo por coerência da V7).
     */
    private HttpRequest buildRequest(Monitor monitor) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(monitor.getUrl()))
                .timeout(Duration.ofMillis(monitor.getTimeoutMillis()))
                .header(USER_AGENT_HEADER, USER_AGENT);
        if (monitor.getMethod() == MonitorMethod.HEAD) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        }
        else {
            builder.GET();
        }
        return builder.build();
    }

    private MonitorExecutionResult classifyResponse(UUID monitorId, Instant startedAt, int status) {
        boolean success = status >= HTTP_SUCCESS_LOWER && status < HTTP_SUCCESS_UPPER;
        Instant finishedAt = clock.instant();
        if (success) {
            return MonitorExecutionResult.of(
                    monitorId,
                    startedAt,
                    finishedAt,
                    status,
                    MonitorOutcome.SUCCESS,
                    null,
                    "Completed with HTTP " + status);
        }
        // Resposta recebida = request completou. 4xx/5xx é falha do alvo com
        // HTTP_ERROR (Prompt 10 refina isso em termos de saúde).
        return MonitorExecutionResult.of(
                monitorId,
                startedAt,
                finishedAt,
                status,
                MonitorOutcome.TARGET_FAILURE,
                FailureCategory.HTTP_ERROR,
                "Target responded with HTTP " + status);
    }

    private MonitorExecutionResult failure(
            UUID monitorId,
            Instant startedAt,
            MonitorOutcome outcome,
            FailureCategory category,
            String message) {
        return MonitorExecutionResult.of(
                monitorId, startedAt, clock.instant(), null, outcome, category, message);
    }
}
