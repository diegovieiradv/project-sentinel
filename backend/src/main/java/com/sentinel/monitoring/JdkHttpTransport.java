package com.sentinel.monitoring;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Transporte HTTP de produção (Prompt 09) — {@code java.net.http.HttpClient}.
 *
 * <p><b>Invariantes de segurança neste ponto:</b></p>
 * <ul>
 *   <li><b>{@code Redirect.NEVER}</b> — um 3xx é devolvido como RESPOSTA
 *       (classificado pelo executor), nunca seguido. Seguir redirecionamento
 *       cegamente é o atalho clássico para um alvo público redirecionar a
 *       checagem para um host interno; se um dia redirects forem permitidos,
 *       cada salto terá de ser revalidado pelo {@code SsrfGuard} antes da
 *       nova conexão (Prompt 09 documenta a limitação).</li>
 *   <li><b>{@code BodyHandlers.discarding()}</b> — o corpo nunca é lido para
 *       memória nem para log/persistência; só o status interessa.</li>
 *   <li><b>Sem cookies, sem Authorization, sem jar</b> — o cliente é
 *       construído limpo; o executor adiciona apenas o {@code User-Agent}
 *       identificável do Sentinel.</li>
 *   <li><b>{@code connectTimeout} de 10s</b> — teto de conexão TCP do
 *       cliente; o limite que importa por checagem é o
 *       {@code HttpRequest.timeout(Monitor.timeoutMillis)}, aplicado pelo
 *       executor e observável em toda a troca de requisição/resposta.</li>
 * </ul>
 *
 * <p><b>Sem dependência nova:</b> usa apenas o cliente do JDK (zero
 * bibliotecas adicionadas ao {@code pom.xml} neste prompt).</p>
 */
@Component
public class JdkHttpTransport implements HttpTransport {

    /** Teto de estabelecimento da conexão (o limite por checagem é o da request). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public JdkHttpTransport() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public int send(HttpRequest request) throws IOException, InterruptedException {
        // discarding(): corpo descartado em streaming — nunca ofString().
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
