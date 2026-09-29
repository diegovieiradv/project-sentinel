package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.project.security.DnsResolver;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proteção SSRF do cadastro de monitores (Prompt 08) + validação de payload do
 * caminho de criação.
 *
 * <p><b>Guard sob teste:</b> nenhuma requisição sai do servidor para o alvo —
 * o guard valida a URL configurada (parse/scheme/host/userinfo/porta) e só
 * resolve DNS quando o host é um NOME, para classificar os endereços. Todo
 * alvo interno/privado/loopback/link-local/multicast/reservado responde
 * {@code 400 MONITOR_TARGET_NOT_ALLOWED} com a mensagem estável — nunca
 * 500, nunca IP/range na resposta.</p>
 *
 * <p><b>DNS stub injetável:</b> só ESTA classe declara o
 * {@code @TestConfiguration} aninhado, então o stub existe exclusivamente
 * neste contexto (as demais classes de monitor continuam com o
 * {@code SystemDnsResolver} e usam literais IP, sem rede). Isso permite
 * exercitar hostname → privado, hostname misto público+privado, hostname
 * público e hostname irresolvível sem Internet e sem flakiness.</p>
 *
 * <p>Também ficam aqui as validações de payload do POST (método, faixas de
 * intervalo/timeout e a coerência timeout &lt; intervalo × 1000) e a ordem
 * documentada das checagens: role → cadeia → pais → SSRF → payload →
 * unicidade.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorSsrfTest extends AbstractMonitorIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    /** Nome único por POST — nada colide dentro de uma mesma cena. */
    private static final AtomicInteger NAMES = new AtomicInteger();

    /**
     * DNS stub: resolve apenas o que o teste mapear; qualquer outro nome
     * lança {@link UnknownHostException} (como um DNS real falhando).
     *
     * <p>Também <b>registra cada tentativa de resolução</b> — é assim que o
     * teste prova que o fast-path de {@code localhost} barrou ANTES de tocar
     * a rede (afirmar que o stub "não tem" o nome não provaria nada, porque
     * um nome ausente e um nome nunca consultado dão o mesmo sintoma).</p>
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

        /** Nomes efetivamente submetidos ao resolver (em ordem). */
        List<String> attemptedHosts() {
            return List.copyOf(attempts);
        }

        void clear() {
            entries.clear();
            attempts.clear();
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

    /**
     * Nested {@code @TestConfiguration} é detectado apenas pela classe que o
     * declara — o stub não vaza para as demais classes de teste. O
     * {@code @Primary} desempata contra o {@code SystemDnsResolver} do
     * component scan na injeção do {@code SsrfGuard} (que pede a interface).
     */
    @TestConfiguration
    static class StubDnsConfiguration {

        @Bean
        @Primary
        StubDnsResolver stubDnsResolver() {
            return new StubDnsResolver();
        }
    }

    @Autowired
    private StubDnsResolver stubDns;

    @BeforeEach
    void resetStubDns() {
        stubDns.clear();
    }

    // --- literais IPv4 bloqueados -------------------------------------------

    @Test
    void blockedIpv4LiteralsReturn400WithStableContract() throws Exception {
        Scene scene = newScene("mon-ssrf-v4", "SSRF V4");

        List<String> blocked = List.of(
                "http://127.0.0.1/",
                "http://127.1.2.3:8080/",
                "http://10.0.0.5/",
                "http://10.255.255.255/",
                "http://172.16.0.9/",
                "http://172.31.255.255/",
                "http://192.168.1.10/",
                "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0/",
                "http://100.64.0.1/",
                "http://192.0.2.10/",
                "http://198.51.100.10/",
                "http://203.0.113.10/",
                "http://198.18.0.1/",
                "http://224.0.0.1/",
                "http://240.0.0.1/",
                "http://255.255.255.255/");
        for (String url : blocked) {
            expectTargetNotAllowed(scene, url);
        }

        expectNothingPersisted(scene);
    }

    // --- literais IPv6 bloqueados -------------------------------------------

    @Test
    void blockedIpv6LiteralsReturn400WithStableContract() throws Exception {
        Scene scene = newScene("mon-ssrf-v6", "SSRF V6");

        List<String> blocked = List.of(
                "http://[::1]/",
                "http://[::]/",
                "http://[fe80::1]/",
                "http://[fc00::1]/",
                "http://[fd00::2:3]/",
                "http://[ff02::1]/",
                "http://[2001:db8::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://[::ffff:10.0.0.1]/",
                "http://[::ffff:192.168.0.10]/");
        for (String url : blocked) {
            expectTargetNotAllowed(scene, url);
        }

        expectNothingPersisted(scene);
    }

    // --- nomes locais (antes de qualquer resolução) -------------------------

    @Test
    void localHostnamesAreBlockedBeforeAnyResolution() throws Exception {
        Scene scene = newScene("mon-ssrf-local", "SSRF Local");

        List<String> blocked = List.of(
                "http://localhost/",
                "http://localhost:8080/health",
                "http://LOCALHOST/",
                "https://LocalHost:9443/api",
                "http://app.localhost/",
                "http://API.LOCALHOST./x");
        for (String url : blocked) {
            expectTargetNotAllowed(scene, url);
        }

        // nenhum hostname foi mapeado no stub: o fast-path barrou antes do DNS
        assertThat(stubDns.attemptedHosts()).as("fast-path de localhost precede qualquer resolução").isEmpty();
        expectNothingPersisted(scene);
    }

    // --- schemes e alvos malformados ----------------------------------------

    @Test
    void nonHttpSchemesAndMalformedTargetsReturn400() throws Exception {
        Scene scene = newScene("mon-ssrf-scheme", "SSRF Scheme");

        List<String> blocked = List.of(
                "file:///etc/passwd",
                "ftp://93.184.216.34/",
                "gopher://93.184.216.34:70/",
                "javascript:alert(1)",
                "mailto:ops@example.com",
                "/health",
                "93.184.216.34",
                "http://",
                "http:///path",
                "not a url",
                "ht!tp://broken");
        for (String url : blocked) {
            expectTargetNotAllowed(scene, url);
        }

        expectNothingPersisted(scene);
    }

    @Test
    void userInfoAndFragmentAreRejected() throws Exception {
        Scene scene = newScene("mon-ssrf-auth", "SSRF Auth");

        // o host nunca é resolvido: as checagens de forma vêm antes do DNS
        expectTargetNotAllowed(scene, "http://user:pass@example.com/");
        expectTargetNotAllowed(scene, "http://admin@example.com/");
        expectTargetNotAllowed(scene, "http://ops:secret@93.184.216.34/");
        expectTargetNotAllowed(scene, "http://93.184.216.34/#frag");
        expectTargetNotAllowed(scene, "https://93.184.216.34/path#frag");

        expectNothingPersisted(scene);
    }

    @Test
    void invalidPortsAreRejectedWhileValidPortIsAccepted() throws Exception {
        Scene scene = newScene("mon-ssrf-port", "SSRF Port");

        expectTargetNotAllowed(scene, "http://93.184.216.34:0/");
        expectTargetNotAllowed(scene, "http://93.184.216.34:65536/");
        expectTargetNotAllowed(scene, "http://93.184.216.34:99999/");
        expectTargetNotAllowed(scene, "http://[::1]:70000/");

        // porta válida e porta default são aceitas
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                body("Porta Válida", "http://93.184.216.34:8080/"));
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                body("Porta Default", "https://93.184.216.34/"));
    }

    @Test
    void missingEmptyOrNullUrlReturns400TargetNotAllowed() throws Exception {
        Scene scene = newScene("mon-ssrf-missing", "SSRF Missing");
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());
        String bearer = scene.org().ownerBearer();

        // ausente
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(
                        "This target is not allowed for security reasons."));

        // em branco
        expectTargetNotAllowed(scene, "");

        // null explícito (Map.of não aceita null)
        Map<String, Object> nullUrl = new LinkedHashMap<>();
        nullUrl.put("name", uniqueName());
        nullUrl.put("url", null);
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(nullUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        expectNothingPersisted(scene);
    }

    // --- alvos públicos aceitos ---------------------------------------------

    @Test
    public void publicIpLiteralsAreAccepted() throws Exception {
        Scene scene = newScene("mon-ssrf-public", "SSRF Public");

        List<String> allowed = List.of(
                "https://93.184.216.34/",
                "http://1.1.1.1/",
                "http://8.8.8.8:53/",
                "http://[2001:4860:4860::8888]/",
                "http://[2606:4700:4700::1111]/health");
        for (int i = 0; i < allowed.size(); i++) {
            createMonitor(
                    scene.org().ownerBearer(),
                    scene.org().organizationId(),
                    scene.projectId(),
                    scene.serviceId(),
                    body("Público " + i, allowed.get(i)));
        }

        assertThat(monitorsOf(scene)).hasSize(allowed.size());
    }

    // --- hostname via DNS stub ----------------------------------------------

    @Test
    void hostnameResolvingOnlyToPrivateAddressesIsBlocked() throws Exception {
        Scene scene = newScene("mon-ssrf-host-private", "SSRF Host Private");
        stubDns.maps("internal.service", "10.0.0.5");

        expectTargetNotAllowed(scene, "http://internal.service/");
        expectTargetNotAllowed(scene, "http://internal.service:8080/api");

        expectNothingPersisted(scene);
    }

    @Test
    void hostnameWithMixedPublicAndPrivateAddressesIsBlocked() throws Exception {
        Scene scene = newScene("mon-ssrf-host-mixed", "SSRF Host Mixed");
        // round-robin clássico: um registro público + um privado não pode
        // "vencer" por qualquer um dos endereços ser público
        stubDns.maps("mixed.example", "93.184.216.34", "10.0.0.5");

        expectTargetNotAllowed(scene, "http://mixed.example/");

        expectNothingPersisted(scene);
    }

    @Test
    void hostnameResolvingOnlyToPublicAddressesIsAccepted() throws Exception {
        Scene scene = newScene("mon-ssrf-host-public", "SSRF Host Public");
        stubDns.maps("ok.example", "93.184.216.34", "93.184.216.35");
        stubDns.maps("ok-v6.example", "2001:4860:4860::8888");

        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                body("Host Público", "http://ok.example/health"));
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                body("Host Público v6", "http://ok-v6.example/"));

        assertThat(monitorsOf(scene)).hasSize(2);
    }

    @Test
    void unresolvableHostnameReturns400WithUnresolvedMessage() throws Exception {
        Scene scene = newScene("mon-ssrf-host-unknown", "SSRF Host Unknown");
        // nada mapeado no stub → UnknownHostException → 400, mas com a
        // mensagem de resolução (sem vazar servidor DNS nem stacktrace)
        expectTargetNotAllowed(scene, "http://unknown.example/", "Unable to resolve target host");

        expectNothingPersisted(scene);
    }

    @Test
    void rejectedUrlIsNeverPersisted() throws Exception {
        Scene scene = newScene("mon-ssrf-persist", "SSRF Persist");
        String bearer = scene.org().ownerBearer();

        expectTargetNotAllowed(scene, "http://127.0.0.1/");

        mockMvc.perform(get(monitorsUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(monitorsOf(scene)).isEmpty();
    }

    // --- ordem documentada das checagens ------------------------------------

    @Test
    void ssrfRunsAfterRoleAndParentChecks() throws Exception {
        Scene scene = newScene("mon-ssrf-order", "SSRF Order");
        String badUrl = "http://169.254.169.254/latest/meta-data/";

        String viewerEmail = uniqueEmail("mon-ssrf-viewer");
        String viewer = registerAndLogin("Viewer SSRF", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        // (1) papel ANTES do guard: VIEWER → 403, nunca 400 (que revelaria
        // que a URL é inválida para quem não pode criar)
        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName(), "url", badUrl))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // (2) serviço inexistente → 404 ANTES do guard
        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(), scene.projectId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName(), "url", badUrl))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // (3) serviço inativo → 409 SERVICE_INACTIVE ANTES do guard
        changeActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                false);
        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName(), "url", badUrl))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"))
                .andExpect(jsonPath("$.message").value(
                        "Cannot create or modify monitors in an inactive service"));
        changeActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                true);

        // (4) projeto inativo → 409 PROJECT_INACTIVE ANTES do guard
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), false);
        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName(), "url", badUrl))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), true);

        // (5) monitor inexistente (PATCH) → 404 ANTES do guard
        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", badUrl))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        expectNothingPersisted(scene);
    }

    @Test
    void ssrfRunsBeforePayloadAndUniquenessOnCreate() throws Exception {
        Scene scene = newScene("mon-ssrf-precedence", "SSRF Precedence");
        String bearer = scene.org().ownerBearer();
        String serviceId = scene.serviceId();
        String created = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), serviceId, "Ocupado");

        // unicidade DEPOIS do guard: nome duplicado + URL interna → 400 do
        // guard, nunca 409 (que confirmaria a existência do nome)
        mockMvc.perform(postCsrf(monitorsUrl(scene.org().organizationId(), scene.projectId(), serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "ocupado",
                                "url", "http://10.0.0.5/"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        // payload DEPOIS do guard: método inválido + URL interna → guard
        mockMvc.perform(postCsrf(monitorsUrl(scene.org().organizationId(), scene.projectId(), serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", uniqueName(),
                                "url", "http://192.168.0.10/",
                                "method", "POST"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        // e a unicidade continua valendo no caminho limpo (URL pública)
        mockMvc.perform(postCsrf(monitorsUrl(scene.org().organizationId(), scene.projectId(), serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "OCUPADO", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_NAME_DUPLICATE"));

        assertThat(monitorsOf(scene)).hasSize(1);
        assertThat(monitorOf(serviceId, created).getUrl()).isEqualTo(PUBLIC_URL);
    }

    // --- guard no update e na reativação ------------------------------------

    @Test
    void patchWithInternalUrlIsRejectedAndKeepsOriginalTarget() throws Exception {
        Scene scene = newScene("mon-ssrf-patch", "SSRF Patch");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Patchável");
        String url = monitorUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        for (String internal : List.of(
                "http://127.0.0.1/", "http://10.0.0.5/", "http://[::1]/", "http://localhost/")) {
            mockMvc.perform(patchCsrf(url)
                            .header(HttpHeaders.AUTHORIZATION, bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", internal))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.message").value(
                            "This target is not allowed for security reasons."));
        }

        // nada foi aplicado: o alvo original continua no banco
        assertThat(monitorOf(scene.serviceId(), monitorId).getUrl()).isEqualTo(PUBLIC_URL);

        // update legítimo troca o alvo normalmente
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "https://1.1.1.1/"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://1.1.1.1/"));
        assertThat(monitorOf(scene.serviceId(), monitorId).getUrl()).isEqualTo("https://1.1.1.1/");
    }

    @Test
    void patchSsrfRunsBeforePayloadValidation() throws Exception {
        Scene scene = newScene("mon-ssrf-patch-order", "SSRF Patch Order");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Ordem PATCH");
        String url = monitorUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // nome em branco + URL interna → o GUARD responde (não o payload)
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   ", "url", "http://10.1.2.3/"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        // intervalo inválido + URL interna → o GUARD responde
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("intervalSeconds", 0, "url", "http://10.1.2.3/"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        // método inválido + URL interna → o GUARD responde
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "DELETE", "url", "http://10.1.2.3/"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));

        // e nada mudou
        assertThat(monitorOf(scene.serviceId(), monitorId).getName()).isEqualTo("Ordem PATCH");
        assertThat(monitorOf(scene.serviceId(), monitorId).getUrl()).isEqualTo(PUBLIC_URL);
        assertThat(monitorOf(scene.serviceId(), monitorId).getIntervalSeconds()).isEqualTo(60);
    }

    @Test
    void reactivationRevalidatesPersistedTarget() throws Exception {
        Scene scene = newScene("mon-ssrf-reactivate", "SSRF Reactivate");
        String bearer = scene.org().ownerBearer();
        stubDns.maps("drift.example", "93.184.216.34");

        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Drift", "http://drift.example/");
        changeMonitorActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId, false);

        // o alvo "mudou de natureza" depois de cadastrado: reativar exige
        // revalidação — o guard roda de novo contra o valor persistido
        stubDns.maps("drift.example", "10.0.0.5");
        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"));
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isFalse();

        // voltou a ser público → a ativação passa
        stubDns.maps("drift.example", "93.184.216.34");
        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    @Test
    void idempotentActivationDoesNotRevalidate() throws Exception {
        Scene scene = newScene("mon-ssrf-idempotent", "SSRF Idempotent");
        String bearer = scene.org().ownerBearer();
        stubDns.maps("stable.example", "93.184.216.34");

        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Estável", "http://stable.example/");
        stubDns.maps("stable.example", "10.0.0.5");

        // repetir o estado atual é no-op ANTES de qualquer revalidação → 200
        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    // --- payload: método ----------------------------------------------------

    @Test
    void unsupportedHttpMethodsReturn400Never500() throws Exception {
        Scene scene = newScene("mon-ssrf-method", "SSRF Method");
        String bearer = scene.org().ownerBearer();

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "TRACE", "CONNECT", "get", "post", "BREW")) {
            mockMvc.perform(postCsrf(monitorsUrl(
                                            scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                            .header(HttpHeaders.AUTHORIZATION, bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "name", uniqueName(),
                                    "url", PUBLIC_URL,
                                    "method", method))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("method: must be GET or HEAD"));
        }

        expectNothingPersisted(scene);
    }

    @Test
    void getAndHeadMethodsAreAccepted() throws Exception {
        Scene scene = newScene("mon-ssrf-method-ok", "SSRF Method OK");
        String bearer = scene.org().ownerBearer();

        Map<String, Object> head = body("Sonda HEAD", PUBLIC_URL);
        head.put("method", "HEAD");
        String headId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), head);

        assertThat(monitorOf(scene.serviceId(), headId).getMethod().name()).isEqualTo("HEAD");

        Map<String, Object> get = body("Sonda GET", PUBLIC_URL);
        get.put("method", "GET");
        String getId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), get);

        assertThat(monitorOf(scene.serviceId(), getId).getMethod().name()).isEqualTo("GET");
    }

    // --- payload: faixas e coerência ----------------------------------------

    @Test
    void intervalAndTimeoutBoundsAreEnforced() throws Exception {
        Scene scene = newScene("mon-ssrf-bounds", "SSRF Bounds");
        String bearer = scene.org().ownerBearer();
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "intervalSeconds", 0),
                "intervalSeconds: must be between 30 and 86400");
        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "intervalSeconds", 29),
                "intervalSeconds: must be between 30 and 86400");
        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "intervalSeconds", 86401),
                "intervalSeconds: must be between 30 and 86400");

        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "timeoutMillis", 0),
                "timeoutMillis: must be between 100 and 30000");
        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "timeoutMillis", 99),
                "timeoutMillis: must be between 100 and 30000");
        assertCreateFailsWith(url, bearer, Map.of("name", uniqueName(), "url", PUBLIC_URL, "timeoutMillis", 30001),
                "timeoutMillis: must be between 100 and 30000");

        // limites inclusive: 30 s, 86400 s, 100 ms e 30000 ms são válidos
        Map<String, Object> lower = body("Limite Inferior", PUBLIC_URL);
        lower.put("intervalSeconds", 30);
        lower.put("timeoutMillis", 100);
        createMonitor(bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), lower);

        Map<String, Object> upper = body("Limite Superior", PUBLIC_URL);
        upper.put("intervalSeconds", 86400);
        upper.put("timeoutMillis", 30000);
        createMonitor(bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), upper);
    }

    @Test
    void timeoutMustBeLessThanIntervalTimes1000() throws Exception {
        Scene scene = newScene("mon-ssrf-coherence", "SSRF Coherence");
        String bearer = scene.org().ownerBearer();
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // 30 s de intervalo com timeout de 30000 ms = checagem engoliria o
        // próprio intervalo → o CHECK da migration tem a mesma regra
        assertCreateFailsWith(
                url,
                bearer,
                Map.of(
                        "name", uniqueName(),
                        "url", PUBLIC_URL,
                        "intervalSeconds", 30,
                        "timeoutMillis", 30000),
                "timeoutMillis: must be less than intervalSeconds * 1000");

        Map<String, Object> justBelow = body("Coerente", PUBLIC_URL);
        justBelow.put("intervalSeconds", 30);
        justBelow.put("timeoutMillis", 29999);
        String coherent = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), justBelow);
        assertThat(monitorOf(scene.serviceId(), coherent).getTimeoutMillis()).isEqualTo(29999);

        // no PATCH a coerência é recalculada sobre os valores EFETIVOS
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "PATCH Coerência");
        String monitorUrl = monitorUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // só o intervalo novo, timeout persistido (5000) → coerente → 200
        mockMvc.perform(patchCsrf(monitorUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("intervalSeconds", 30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(30));

        // intervalo + timeout que se tocam → 400
        mockMvc.perform(patchCsrf(monitorUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("intervalSeconds", 30, "timeoutMillis", 30000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "timeoutMillis: must be less than intervalSeconds * 1000"));

        // o PATCH reprovado não alterou nada
        assertThat(monitorOf(scene.serviceId(), monitorId).getIntervalSeconds()).isEqualTo(30);
        assertThat(monitorOf(scene.serviceId(), monitorId).getTimeoutMillis()).isEqualTo(5000);
    }

    @Test
    void defaultsAreAppliedWhenOptionalFieldsAreOmitted() throws Exception {
        Scene scene = newScene("mon-ssrf-defaults", "SSRF Defaults");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Defaults");

        com.sentinel.project.monitor.model.Monitor persisted = monitorOf(scene.serviceId(), monitorId);
        assertThat(persisted.getMethod().name()).isEqualTo("GET");
        assertThat(persisted.getIntervalSeconds()).isEqualTo(60);
        assertThat(persisted.getTimeoutMillis()).isEqualTo(5000);
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getType().name()).isEqualTo("HTTP");
        assertThat(persisted.getUrl()).isEqualTo(PUBLIC_URL);
    }

    // --- apoio ----------------------------------------------------------------

    private static Map<String, Object> body(String name, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("url", url);
        return body;
    }

    private static String uniqueName() {
        return "Monitor " + NAMES.incrementAndGet();
    }

    private void expectTargetNotAllowed(Scene scene, String url) throws Exception {
        expectTargetNotAllowed(
                scene, url, "This target is not allowed for security reasons.");
    }

    private void expectTargetNotAllowed(Scene scene, String url, String message) throws Exception {
        mockMvc.perform(postCsrf(monitorsUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", uniqueName(), "url", url))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_TARGET_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private void assertCreateFailsWith(String url, String bearer, Map<String, Object> payload, String message)
            throws Exception {
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private void expectNothingPersisted(Scene scene) {
        assertThat(monitorsOf(scene)).isEmpty();
    }

    private List<com.sentinel.project.monitor.model.Monitor> monitorsOf(Scene scene) {
        return monitorRepository
                .findByServiceId(
                        UUID.fromString(scene.serviceId()), PageRequest.of(0, 100))
                .getContent();
    }

}
