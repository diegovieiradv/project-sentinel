package com.sentinel.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sentinel.identity.JwtProperties;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/**
 * Ciclo de vida do refresh token contra a cadeia real (Prompt 03):
 * emissão em cookie HttpOnly, rotação atômica, detecção de reuso,
 * expiração, logout idempotente e persistência apenas do hash.
 *
 * <p>CSRF é exercitado com o contrato real (cookie {@code XSRF-TOKEN} + header
 * {@code X-XSRF-TOKEN}), nunca com {@code .with(csrf())} — que desligaria o
 * {@code CsrfFilter} que estamos testando.</p>
 *
 * <p>Classe de contexto própria: o {@code ServiceConnectionContextCustomizer}
 * é derivado da instância do container, então esta classe recebe seu próprio
 * PostgreSQL do Testcontainers e seu próprio {@code ApplicationContext}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenIntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String REFRESH_COOKIE = "refresh_token";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProperties jwtProperties;

    private String csrfToken;

    // --- helpers ----------------------------------------------------------

    private String json(Map<?, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private String csrf() throws Exception {
        if (this.csrfToken == null) {
            MvcResult result = mockMvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
            assertThat(cookie).as("GET must materialize the XSRF-TOKEN cookie").isNotNull();
            this.csrfToken = cookie.getValue();
        }
        return this.csrfToken;
    }

    private MockHttpServletRequestBuilder postCsrf(String url) throws Exception {
        String token = csrf();
        return post(url)
                .cookie(new Cookie(CSRF_COOKIE, token))
                .header(CSRF_HEADER, token);
    }

    private Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
        assertThat(cookie).as("endpoint must set the refresh_token cookie").isNotNull();
        return cookie;
    }

    private void registerAndLogin(String email, String password) throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Refresh", "email", email, "password", password))))
                .andExpect(status().isCreated());
        mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return refreshCookie(result);
    }

    private MvcResult refreshExpecting(int expectedStatus, Cookie... refreshCookies) throws Exception {
        MockHttpServletRequestBuilder request = postCsrf("/api/v1/auth/refresh");
        if (refreshCookies.length > 0) {
            request.cookie(refreshCookies);
        }
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private String hash(String rawToken) {
        return RefreshTokenService.sha256Hex(rawToken);
    }

    private boolean tokenRevoked(String rawToken) {
        Boolean revoked = jdbcTemplate.queryForObject(
                "SELECT revoked FROM refresh_tokens WHERE token_hash = ?", Boolean.class, hash(rawToken));
        assertThat(revoked).as("token row must exist for %s", rawToken).isNotNull();
        return revoked;
    }

    /** Conta os refresh tokens ainda ativos (não revogados) de um usuário. */
    private Integer activeTokensOf(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id"
                        + " WHERE u.email = ? AND rt.revoked = false",
                Integer.class, email);
    }

    /**
     * Apresenta o MESMO refresh token em {@code attempts} requisições simultâneas
     * e devolve os resultados na ordem das threads.
     */
    private List<MvcResult> refreshConcurrently(Cookie refresh, int attempts) throws Exception {
        // CSRF materializado ANTES das threads (happen-before do latch)
        csrf();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>(attempts);
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(postCsrf("/api/v1/auth/refresh").cookie(refresh))
                            .andReturn();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>(attempts);
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
        finally {
            pool.shutdownNow();
        }
    }

    // --- emissão ----------------------------------------------------------

    @Test
    void loginPersistsOnlySha256HashWithSevenDayExpiry() throws Exception {
        registerAndLogin("emit@example.com", "secret123");
        Cookie refresh = login("emit@example.com", "secret123");
        String raw = refresh.getValue();

        assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]+");

        // o valor bruto nunca toca o banco
        Integer rawOccurrences = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, raw);
        assertThat(rawOccurrences).isZero();

        Integer hashOccurrences = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, hash(raw));
        assertThat(hashOccurrences).isEqualTo(1);

        Integer hashLength = jdbcTemplate.queryForObject(
                "SELECT LENGTH(token_hash) FROM refresh_tokens WHERE token_hash = ?", Integer.class, hash(raw));
        assertThat(hashLength).isEqualTo(64);

        Timestamp expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE token_hash = ?", Timestamp.class, hash(raw));
        assertThat(expiresAt.toInstant()).isBetween(
                Instant.now().plus(Duration.ofDays(6)),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));

        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND revoked = false", Integer.class,
                hash(raw));
        assertThat(activeRows).isEqualTo(1);
    }

    @Test
    void refreshTokenNeverAppearsInResponseBody() throws Exception {
        registerAndLogin("nowww@example.com", "secret123");

        MvcResult login = mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nowww@example.com", "password", "secret123"))))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> body = responseBody(login);
        String raw = refreshCookie(login).getValue();

        assertThat(body).doesNotContainKey("refreshToken");
        assertThat(body).doesNotContainKey("refresh_token");
        assertThat(objectMapper.writeValueAsString(body))
                .as("the raw refresh token must never leak into the JSON body")
                .doesNotContain(raw);
    }

    // --- rotação ----------------------------------------------------------

    @Test
    void refreshRotatesCookieAndReturnsWorkingAccessToken() throws Exception {
        registerAndLogin("rotate@example.com", "secret123");
        Cookie first = login("rotate@example.com", "secret123");

        MvcResult rotated = refreshExpecting(200, first);
        Cookie second = refreshCookie(rotated);

        assertThat(second.getValue())
                .as("rotation must issue a brand new token")
                .isNotEqualTo(first.getValue());
        assertThat(second.isHttpOnly()).isTrue();
        assertThat(second.getPath()).isEqualTo("/api/v1/auth");

        Map<String, Object> body = responseBody(rotated);
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        String newAccess = (String) body.get("accessToken");
        assertThat(newAccess).isNotBlank();

        // o access token novo autentica
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("rotate@example.com"));

        // o token antigo não é mais aceito
        MvcResult replay = refreshExpecting(401, first);
        assertThat(responseBody(replay).get("code")).isEqualTo(RefreshTokenService.REVOKED_CODE);
    }

    @Test
    void reuseOfRotatedTokenRevokesEveryTokenOfThatUser() throws Exception {
        registerAndLogin("reuse@example.com", "secret123");
        Cookie original = login("reuse@example.com", "secret123");
        Cookie rotated = refreshCookie(refreshExpecting(200, original));

        // apresentar o token já rotacionado = sinal de roubo → revoga tudo
        MvcResult reuse = refreshExpecting(401, original);
        assertThat(responseBody(reuse).get("code")).isEqualTo(RefreshTokenService.REVOKED_CODE);
        assertThat(responseBody(reuse).get("message")).isEqualTo(RefreshTokenService.REVOKED_MESSAGE);

        // o token vigente cai junto
        MvcResult afterReuse = refreshExpecting(401, rotated);
        assertThat(responseBody(afterReuse).get("code")).isEqualTo(RefreshTokenService.REVOKED_CODE);

        assertThat(activeTokensOf("reuse@example.com"))
                .as("reuse must revoke every token of that user")
                .isZero();
    }

    @Test
    void concurrentRefreshWithSameCookieElectsExactlyOneWinnerAndNeverTwoActiveTokens()
            throws Exception {
        registerAndLogin("conc@example.com", "secret123");
        Cookie original = login("conc@example.com", "secret123");

        int attempts = 4;
        List<MvcResult> results = refreshConcurrently(original, attempts);

        // o UPDATE condicional tem UM vencedor; os perdedores caem no caminho
        // de reuso (token já revogado reapresentado) → 401 REVOKED
        List<Integer> statuses = new ArrayList<>(attempts);
        for (MvcResult result : results) {
            statuses.add(result.getResponse().getStatus());
        }
        assertThat(statuses)
                .as("exactly one rotation may win; every other racer gets 401")
                .containsExactlyInAnyOrder(200, 401, 401, 401);

        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 200) {
                Map<String, Object> body = responseBody(result);
                assertThat((String) body.get("accessToken")).isNotBlank();
                assertThat(refreshCookie(result).getValue())
                        .as("the winner must receive a rotated cookie")
                        .isNotEqualTo(original.getValue());
            }
            else {
                assertThat(responseBody(result).get("code"))
                        .isEqualTo(RefreshTokenService.REVOKED_CODE);
            }
        }

        // o token disputado ficou revogado e NUNCA existem dois ativos
        assertThat(tokenRevoked(original.getValue())).isTrue();
        assertThat(activeTokensOf("conc@example.com"))
                .as("concurrent refresh must never leave two active tokens")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void refreshWithoutCookieReturns401Invalid() throws Exception {
        MvcResult result = refreshExpecting(401);
        assertThat(responseBody(result).get("code")).isEqualTo(RefreshTokenService.INVALID_CODE);
        assertThat(responseBody(result).get("message")).isEqualTo(RefreshTokenService.INVALID_MESSAGE);
        assertThat(responseBody(result).get("path")).isEqualTo("/api/v1/auth/refresh");
    }

    @Test
    void forgedRefreshTokenReturns401Invalid() throws Exception {
        Cookie forged = new Cookie(REFRESH_COOKIE, "a".repeat(43));
        MvcResult result = refreshExpecting(401, forged);
        assertThat(responseBody(result).get("code")).isEqualTo(RefreshTokenService.INVALID_CODE);
    }

    @Test
    void expiredRefreshTokenReturns401Expired() throws Exception {
        registerAndLogin("expired@example.com", "secret123");
        Cookie refresh = login("expired@example.com", "secret123");

        jdbcTemplate.update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 hour'"
                        + " WHERE token_hash = ?",
                hash(refresh.getValue()));

        MvcResult result = refreshExpecting(401, refresh);
        assertThat(responseBody(result).get("code")).isEqualTo(RefreshTokenService.EXPIRED_CODE);
        assertThat(responseBody(result).get("message")).isEqualTo(RefreshTokenService.EXPIRED_MESSAGE);
    }

    // --- logout -------------------------------------------------------------

    @Test
    void logoutRevokesPresentedTokenClearsCookieAndIsIdempotent() throws Exception {
        registerAndLogin("bye@example.com", "secret123");
        Cookie refresh = login("bye@example.com", "secret123");

        MvcResult logout = mockMvc.perform(postCsrf("/api/v1/auth/logout")
                        .cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"))
                .andReturn();

        assertThat(tokenRevoked(refresh.getValue())).isTrue();

        Cookie cleared = refreshCookie(logout);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cleared.getAttribute("SameSite")).isEqualTo("Strict");

        // revogado: o refresh anterior deixa de valer
        MvcResult afterLogout = refreshExpecting(401, refresh);
        assertThat(responseBody(afterLogout).get("code")).isEqualTo(RefreshTokenService.REVOKED_CODE);

        // idempotente: de novo sem cookie e de novo com o mesmo cookie → 200
        mockMvc.perform(postCsrf("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));
        mockMvc.perform(postCsrf("/api/v1/auth/logout").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));
    }

    @Test
    void logoutWithoutCookieStillReturns200() throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));
    }

    @Test
    void fullFlowLoginRefreshMeLogoutRefreshFails() throws Exception {
        registerAndLogin("flow@example.com", "secret123");

        MvcResult loginResult = mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "flow@example.com", "password", "secret123"))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie afterLogin = refreshCookie(loginResult);
        String firstAccess = (String) responseBody(loginResult).get("accessToken");
        assertThat(firstAccess).isNotBlank();

        MvcResult rotated = refreshExpecting(200, afterLogin);
        Cookie afterRefresh = refreshCookie(rotated);
        // O access token é determinístico (HS256, claims só com sub/iat/exp e
        // iat com resolução de 1s): dois tokens mintados no mesmo segundo são
        // idênticos byte a byte. Quem prova a rotação é o cookie, não o JWT.
        assertThat(afterRefresh.getValue())
                .as("refresh must rotate the refresh cookie")
                .isNotEqualTo(afterLogin.getValue());
        String secondAccess = (String) responseBody(rotated).get("accessToken");
        assertThat(secondAccess).isNotBlank();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow@example.com"));

        mockMvc.perform(postCsrf("/api/v1/auth/logout").cookie(afterRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));

        assertThat(responseBody(refreshExpecting(401, afterRefresh)).get("code"))
                .isEqualTo(RefreshTokenService.REVOKED_CODE);
    }

    // --- convenções de configuração ---------------------------------------

    @Test
    void cookieDefaultsMatchDocumentedContract() {
        assertThat(jwtProperties.cookie().name()).isEqualTo(REFRESH_COOKIE);
        assertThat(jwtProperties.cookie().path()).isEqualTo("/api/v1/auth");
        assertThat(jwtProperties.cookie().sameSite()).isEqualTo("Strict");
        assertThat(jwtProperties.refreshTtlDays()).isEqualTo(7);
        assertThat(jwtProperties.refreshTtlSeconds()).isEqualTo(7 * 24 * 3600L);
    }
}
