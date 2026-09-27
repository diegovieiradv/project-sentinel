package com.sentinel.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.sentinel.error.ApiException;
import com.sentinel.identity.JwtConfiguration;
import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;
import com.sentinel.identity.user.UserService;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/**
 * Fluxos completos de identity (Prompt 03) contra contexto real:
 * Spring Security stateless (Bearer + CSRF double-submit + CORS), JPA,
 * Flyway V3 e Testcontainers PostgreSQL.
 *
 * <p><b>CSRF real, não simulado</b>: os testes não usam {@code .with(csrf())}
 * porque isso substituiria o {@code CsrfFilter} da cadeia sob teste. Em vez
 * disso seguem o contrato do frontend — um GET materializa o cookie
 * {@code XSRF-TOKEN} e cada POST ecoa o valor no header {@code X-XSRF-TOKEN}.
 * </p>
 *
 * <p>Lifecycle manual do container — mesmo padrão dos demais testes de
 * integração do projeto (uma classe de contexto por container).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";
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
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtConfiguration.ResolvedJwtSecret jwtSecret;

    /** Cookie CSRF materializado por este teste (o servidor não rotaciona). */
    private String csrfToken;

    // --- helpers ----------------------------------------------------------

    private String json(Map<?, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    /**
     * Lê o cookie {@code XSRF-TOKEN} emitido por um GET público.
     *
     * <p>O cookie não é HttpOnly justamente porque o frontend precisa lê-lo
     * para ecoá-lo em {@code X-XSRF-TOKEN} (double-submit).</p>
     */
    private String csrf() throws Exception {
        if (this.csrfToken == null) {
            MvcResult result = mockMvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
            assertThat(cookie).as("GET must materialize the XSRF-TOKEN cookie").isNotNull();
            assertThat(cookie.getValue()).isNotBlank();
            this.csrfToken = cookie.getValue();
        }
        return this.csrfToken;
    }

    /** POST com o par cookie+header exigido pelo CsrfFilter da cadeia real. */
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

    private MvcResult register(String name, String email, String password) throws Exception {
        return mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult loginExpecting(int expectedStatus, String email, String password) throws Exception {
        return mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult loginOk(String email, String password) throws Exception {
        return loginExpecting(200, email, password);
    }

    private String accessToken(MvcResult loginResult) throws Exception {
        String token = (String) responseBody(loginResult).get("accessToken");
        assertThat(token).as("login must return an access token").isNotBlank();
        return token;
    }

    private String bearer(MvcResult loginResult) throws Exception {
        return "Bearer " + accessToken(loginResult);
    }

    private MockHttpServletRequestBuilder meWith(String bearer) {
        return get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer);
    }

    /** Assina um JWT arbitrário para os casos negativos de Bearer. */
    private String signedToken(String subject, Instant issuedAt, Instant expiresAt, SecretKey key) {
        JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private SecretKey hmacKey(String rawSecret) {
        return new SecretKeySpec(rawSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // --- register ---------------------------------------------------------

    @Test
    void registerReturns201WithUserEnvelopeAndNoSecrets() throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Alice Adapter",
                                "email", "alice@example.com",
                                "password", "secret123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Alice Adapter"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.tokenType").doesNotExist());
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Bob",
                                "email", "not-an-email",
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void registerWithBlankNameReturns400() throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", " ",
                                "email", "blank-name@example.com",
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registerWithOversizedFieldsReturns400() throws Exception {
        // Teto de tamanho (255) adicionado no Prompt 03 — apenas para limitar
        // o que chega ao banco; NÃO muda a política de senha.
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "n".repeat(256),
                                "email", "big@example.com",
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Big",
                                "email", "e".repeat(250) + "@example.com",
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registerDuplicateEmailReturns409WithEmailAlreadyExists() throws Exception {
        register("Dup", "dup@example.com", "secret123");

        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Dup Again",
                                "email", "dup@example.com",
                                "password", "another123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void registerWithWeakPasswordReturns400WithPolicyMessage() throws Exception {
        // sem dígito
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Weak",
                                "email", "weak1@example.com",
                                "password", "password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.message").value(UserService.PASSWORD_POLICY_MESSAGE));

        // curta (7 chars com letra e dígito)
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Weak",
                                "email", "weak2@example.com",
                                "password", "Abc123d"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void registerWithMalformedJsonReturns400ErrorShapeWithoutParserInternals() throws Exception {
        // JSON truncado ("{"name": ") nunca pode virar 500 nem vazar o
        // detalhe do parser: é entrada inválida do cliente → 400 no shape padrão
        MvcResult result = mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Broken\", \"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("JSON parse")
                .doesNotContain("end-of-input")
                .doesNotContain("Exception")
                .doesNotContain("stackTrace");
    }

    @Test
    void registerWithWrongJsonTypeReturns400ErrorShape() throws Exception {
        // objeto onde o DTO espera string: desserialização impossível → 400,
        // não o 500 do catch-all
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": {\"nested\": \"object\"},"
                                + " \"email\": \"nested-type@example.com\","
                                + " \"password\": \"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void registerNormalizesEmailToLowercase() throws Exception {
        // bean validation @Email roda no controller (rejeita espaços); a
        // normalização de caso acontece no service antes de qualquer operação.
        mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Case",
                                "email", "Foo@Bar.COM",
                                "password", "secret123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("foo@bar.com"));

        assertThat(userRepository.existsByEmail("foo@bar.com")).isTrue();
        assertThat(userRepository.existsByEmail("Foo@Bar.COM")).isFalse();
    }

    @Test
    void registerPersistsBcryptHashNotRawPassword() throws Exception {
        register("Hash", "hash@example.com", "secret123");

        User stored = userRepository.findByEmail("hash@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("secret123", stored.getPasswordHash())).isTrue();
    }

    // --- CSRF double-submit ------------------------------------------------

    @Test
    void getMaterializesReadableXsrfCookieForFrontend() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(cookie).as("GET must issue the XSRF-TOKEN cookie").isNotNull();
        assertThat(cookie.isHttpOnly())
                .as("the frontend must read XSRF-TOKEN to echo it back")
                .isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).as("session cookie, not persisted").isEqualTo(-1);
        assertThat(cookie.getValue()).isNotBlank();
    }

    @Test
    void postWithoutCsrfTokenReturns403ErrorShape() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nobody@example.com", "password", "secret123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"));
    }

    @Test
    void postWithMismatchedCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie(CSRF_COOKIE, csrf()))
                        .header(CSRF_HEADER, "not-the-cookie-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nobody@example.com", "password", "secret123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void preflightAdvertisesXsrfHeaderToCrossOriginBrowser() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers
                        .containsStringIgnoringCase("x-xsrf-token")));
    }

    @Test
    void preflightAdmitsPatchAndOrganizationIdHeaderForTenantModule() throws Exception {
        // Prompt 04: PATCH (role de membro) e X-Organization-Id (Tenant Context)
        // precisam sobreviver ao preflight — sem isso o frontend é bloqueado
        mockMvc.perform(options("/api/v1/organizations")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "content-type,x-xsrf-token,x-organization-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsStringIgnoringCase("x-organization-id")));
    }

    // --- login / sessionless ----------------------------------------------

    @Test
    void loginReturnsBearerAndHttpOnlyRefreshCookieWithoutSession() throws Exception {
        register("Carol", "carol@example.com", "secret123");

        MvcResult loginResult = loginOk("carol@example.com", "secret123");

        // corpo: access token stateless, nunca o refresh token
        Map<String, Object> body = responseBody(loginResult);
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat((String) body.get("accessToken")).contains(".");
        assertThat(body.get("refreshToken")).isNull();
        assertThat(body).doesNotContainKey("refreshToken");

        // cookie: HttpOnly, Path restrito ao namespace de auth, SameSite=Strict
        Cookie refresh = refreshCookie(loginResult);
        assertThat(refresh.getValue()).isNotBlank();
        assertThat(refresh.isHttpOnly()).as("JS must never read the refresh token").isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
        assertThat(refresh.getSecure()).as("secure only in prod").isFalse();
        assertThat(refresh.getMaxAge()).isEqualTo(7 * 24 * 3600);
        assertThat(refresh.getAttribute("SameSite")).isEqualTo("Strict");

        // stateless: nenhuma sessão server-side
        assertThat(loginResult.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(loginResult.getRequest().getSession(false)).as("no JSESSIONID at all").isNull();
    }

    @Test
    void meWithBearerReturnsUser() throws Exception {
        register("Dana", "dana@example.com", "secret123");
        MvcResult loginResult = loginOk("dana@example.com", "secret123");

        mockMvc.perform(meWith(bearer(loginResult)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dana@example.com"))
                .andExpect(jsonPath("$.name").value("Dana"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void loginWithWrongPasswordReturns401GenericMessage() throws Exception {
        register("Dan", "dan@example.com", "secret123");

        MvcResult wrongPassword = loginExpecting(401, "dan@example.com", "wrong-pass");
        assertThat(responseBody(wrongPassword).get("code")).isEqualTo("INVALID_CREDENTIALS");

        MvcResult unknownUser = loginExpecting(401, "ghost@example.com", "whatever1");

        assertThat(responseBody(wrongPassword).get("message"))
                .as("mensagem idêntica para senha errada e usuário inexistente")
                .isEqualTo(responseBody(unknownUser).get("message"))
                .isEqualTo(UserService.INVALID_CREDENTIALS_MESSAGE);
    }

    @Test
    void loginNormalizesEmailBeforeLookup() throws Exception {
        register("Eve", "eve@example.com", "secret123");

        mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "EVE@Example.COM", "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("eve@example.com"));
    }

    @Test
    void loginWithInvalidPayloadReturns400() throws Exception {
        mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "not-email", "password", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void inactiveUserCannotLoginAndGetsSameGeneric401() throws Exception {
        register("Frank", "frank@example.com", "secret123");
        jdbcTemplate.update("UPDATE users SET active = false WHERE email = ?", "frank@example.com");

        MvcResult inactive = loginExpecting(401, "frank@example.com", "secret123");
        assertThat(responseBody(inactive).get("code")).isEqualTo("INVALID_CREDENTIALS");

        MvcResult unknownUser = loginExpecting(401, "nobody@example.com", "whatever1");
        assertThat(responseBody(inactive).get("message"))
                .isEqualTo(responseBody(unknownUser).get("message"));
    }

    // --- Bearer: casos negativos -------------------------------------------

    @Test
    void meWithoutAuthenticationReturns401UnauthorizedShape() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/me"));
    }

    @Test
    void meWithMalformedBearerReturns401() throws Exception {
        mockMvc.perform(meWith("Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meWithTokenSignedByAnotherKeyReturns401() throws Exception {
        String forged = signedToken(
                UUID.randomUUID().toString(),
                Instant.now(),
                Instant.now().plusSeconds(900),
                hmacKey("another-secret-another-secret-other!"));

        mockMvc.perform(meWith("Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meWithUnknownSubjectReturns401() throws Exception {
        String unknownSubject = signedToken(
                UUID.randomUUID().toString(),
                Instant.now(),
                Instant.now().plusSeconds(900),
                hmacKey(jwtSecret.value()));

        mockMvc.perform(meWith("Bearer " + unknownSubject))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meWithExpiredTokenReturns401() throws Exception {
        // expirado há 1h — bem além do clock skew de 60s do validador.
        // issuedAt precisa ser anterior a exp: o encoder rejeita iat == exp.
        Instant past = Instant.now().minusSeconds(3600);
        String expired = signedToken(UUID.randomUUID().toString(), past.minusSeconds(600), past,
                hmacKey(jwtSecret.value()));

        mockMvc.perform(meWith("Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meWithTokenOfDeactivatedUserReturns401() throws Exception {
        register("Ivan", "ivan@example.com", "secret123");
        MvcResult loginResult = loginOk("ivan@example.com", "secret123");
        String bearer = bearer(loginResult);

        mockMvc.perform(meWith(bearer)).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE users SET active = false WHERE email = ?", "ivan@example.com");

        mockMvc.perform(meWith(bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- resolveCurrentUser (unidade) --------------------------------------

    @Test
    void resolveCurrentUserSupportsEntityAndStringPrincipals() throws Exception {
        register("Grace", "grace@example.com", "secret123");

        User user = userRepository.findByEmail("grace@example.com").orElseThrow();

        UserResponse byEntity = userService.resolveCurrentUser(user);
        assertThat(byEntity.email()).isEqualTo("grace@example.com");

        UserResponse byStringPrincipal = userService.resolveCurrentUser("grace@example.com");
        assertThat(byStringPrincipal.id()).isEqualTo(user.getId());

        assertThatThrownBy(() -> userService.resolveCurrentUser("ghost@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Authentication required");

        assertThatThrownBy(() -> userService.resolveCurrentUser(null))
                .isInstanceOf(ApiException.class);
    }

    // --- públicos / protegidos / CORS / 404 -------------------------------

    @Test
    void healthIsPublicWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protectedUnknownApiRouteWithoutAuthReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/does-not-exist"));
    }

    @Test
    void protectedUnknownApiRouteWithBearerReturns404() throws Exception {
        register("Hank", "hank@example.com", "secret123");
        MvcResult loginResult = loginOk("hank@example.com", "secret123");

        mockMvc.perform(get("/api/v1/does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginResult)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/does-not-exist"));
    }

    @Test
    void preflightOptionsIsAllowedForFrontendOriginWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void corsHeadersArePresentForFrontendOriginOnPublicEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/health").header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void foreignOriginIsRejectedWithoutCorsAllowHeader() throws Exception {
        // DefaultCorsProcessor rejeita origem não permitida com 403 "Invalid CORS request"
        // e não emite Access-Control-Allow-Origin.
        mockMvc.perform(get("/api/v1/health").header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // --- headers de segurança (defaults do Spring Security) -----------------

    @Test
    void securityHeadersArePresentOnResponses() throws Exception {
        // Defaults do HeadersConfigurer (não reconfigurados por causa do
        // teste): nosniff impede MIME-sniffing e X-Frame-Options bloqueia
        // clickjacking (DENY, conforme o default documentado do Spring Security)
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
