package com.sentinel.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;
import com.sentinel.identity.user.UserService;

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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/**
 * Fluxos completos de identity (Prompt 02) contra contexto real:
 * Spring Security (filter chain + CORS + sessão), JPA, Flyway V2 e
 * Testcontainers PostgreSQL.
 *
 * Lifecycle manual do container — mesmo padrão do SentinelApplicationTests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";

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

    // --- helpers ----------------------------------------------------------

    private String json(Map<?, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Map<?, ?> responseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private MvcResult register(String name, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult loginExpecting(int expectedStatus, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MockHttpSession loginSession(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("login must establish an HTTP session").isNotNull();
        return session;
    }

    // --- register ---------------------------------------------------------

    @Test
    void registerReturns201WithUserEnvelopeAndNoSecrets() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
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
                .andExpect(jsonPath("$.tokenType").doesNotExist());
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
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
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", " ",
                                "email", "blank-name@example.com",
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registerDuplicateEmailReturns409WithEmailAlreadyExists() throws Exception {
        register("Dup", "dup@example.com", "secret123");

        mockMvc.perform(post("/api/v1/auth/register")
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
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Weak",
                                "email", "weak1@example.com",
                                "password", "password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.message").value(UserService.PASSWORD_POLICY_MESSAGE));

        // curta (7 chars com letra e dígito)
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Weak",
                                "email", "weak2@example.com",
                                "password", "Abc123d"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void registerNormalizesEmailToLowercase() throws Exception {
        // bean validation @Email roda no controller (rejeita espaços); a
        // normalização de caso acontece no service antes de qualquer operação.
        mockMvc.perform(post("/api/v1/auth/register")
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

    // --- login ------------------------------------------------------------

    @Test
    void loginReturns200AndSessionAuthorizesMe() throws Exception {
        register("Carol", "carol@example.com", "secret123");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "carol@example.com", "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("carol@example.com"))
                .andExpect(jsonPath("$.user.name").value("Carol"))
                .andExpect(jsonPath("$.tokenType").value("session"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).as("login must establish an HTTP session").isNotNull();

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("carol@example.com"))
                .andExpect(jsonPath("$.name").value("Carol"))
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

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "EVE@Example.COM", "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("eve@example.com"));
    }

    @Test
    void loginWithInvalidPayloadReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
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

    // --- me / proteção ----------------------------------------------------

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
    void protectedUnknownApiRouteWithSessionReturns404() throws Exception {
        register("Hank", "hank@example.com", "secret123");
        MockHttpSession session = loginSession("hank@example.com", "secret123");

        mockMvc.perform(get("/api/v1/does-not-exist").session(session))
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
}
