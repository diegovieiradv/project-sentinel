package com.sentinel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

/**
 * Base compartilhada dos testes de organização (Prompt 04) — também estendida
 * pelos testes de projeto (Prompt 06), daí {@code public}.
 *
 * <p>Sem anotações Spring e SEM ciclo de vida: cada subclasse declara o seu
 * próprio {@code @SpringBootTest}/{@ServiceConnection}/container (mesmo padrão
 * do AuthIntegrationTest — uma classe de contexto por container). Aqui ficam
 * apenas helpers puros: JSON, CSRF double-submit real e o fluxo
 * register → login → Bearer.</p>
 *
 * <p><b>CSRF real, nunca {@code .with(csrf())}</b>: o CsrfFilter da cadeia sob
 * teste precisa ver o contrato do frontend — GET materializa o cookie
 * {@code XSRF-TOKEN}, demais métodos ecoam o valor em {@code X-XSRF-TOKEN}.</p>
 */
public abstract class AbstractTenantIntegrationTest {

    static final String CSRF_COOKIE = "XSRF-TOKEN";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";
    static final String ORGANIZATION_HEADER = "X-Organization-Id";
    static final String PASSWORD = "secret123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Cookie CSRF materializado por este teste (o servidor não rotaciona). */
    private String csrfToken;

    // --- JSON ---------------------------------------------------------------

    protected String json(Map<?, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> responseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    // --- CSRF (double-submit real) ------------------------------------------

    /** Cookie materializado por um GET público; cacheado por instância de teste. */
    protected String csrf() throws Exception {
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

    protected MockHttpServletRequestBuilder postCsrf(String url) throws Exception {
        String token = csrf();
        return post(url).cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    protected MockHttpServletRequestBuilder patchCsrf(String url) throws Exception {
        String token = csrf();
        return patch(url).cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    protected MockHttpServletRequestBuilder deleteCsrf(String url) throws Exception {
        String token = csrf();
        return delete(url).cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    protected MockHttpServletRequestBuilder putCsrf(String url) throws Exception {
        String token = csrf();
        return put(url).cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    // --- identidade ---------------------------------------------------------

    /** Registra um usuário (201) — e-mail deve ser único por teste. */
    protected MvcResult register(String name, String email) throws Exception {
        return mockMvc.perform(postCsrf("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "email", email, "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    protected MvcResult loginOk(String email) throws Exception {
        return mockMvc.perform(postCsrf("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
    }

    protected String bearer(MvcResult loginResult) throws Exception {
        String token = (String) responseBody(loginResult).get("accessToken");
        assertThat(token).as("login must return an access token").isNotBlank();
        return "Bearer " + token;
    }

    /** Atalho do cenário padrão: novo usuário autenticado. */
    protected String registerAndLogin(String name, String email) throws Exception {
        register(name, email);
        return bearer(loginOk(email));
    }

    // --- domínio ------------------------------------------------------------

    /** POST /api/v1/organizations → id da organização criada (201). */
    protected String createOrganization(String bearerToken, String name) throws Exception {
        MvcResult result = mockMvc.perform(postCsrf("/api/v1/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the organization id").isNotBlank();
        return id;
    }

    /** POST .../members → 201 (papel explícito, sem default). */
    protected void addMember(
            String bearerToken, String organizationId, String email, String role) throws Exception {
        mockMvc.perform(postCsrf("/api/v1/organizations/" + organizationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }
}
