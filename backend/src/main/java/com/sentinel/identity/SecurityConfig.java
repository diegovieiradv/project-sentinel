package com.sentinel.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.sentinel.error.ErrorResponse;
import com.sentinel.identity.auth.UserJwtAuthenticationConverter;
import com.sentinel.identity.user.UserRepository;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Segurança do Prompt 03 — Identity stateless.
 *
 * <p><b>Auth</b>: nenhum servidor de sessão. Access token HS256 no header
 * {@code Authorization: Bearer} ({@code SessionCreationPolicy.STATELESS} +
 * converter JWT → entidade {@code User}); refresh token opaco em cookie
 * {@code HttpOnly} rotacionado por {@code /api/v1/auth/refresh}.</p>
 *
 * <p><b>CSRF</b>: habilitado em double-submit com cookie legível pelo JS
 * ({@code XSRF-TOKEN} / header {@code X-XSRF-TOKEN}), via
 * {@code csrf.spa()}. O cookie é materializado em toda requisição MVC pelo
 * {@code CsrfTokenCookieAdvice}; todo POST exige o header correspondente.</p>
 *
 * <p><b>CORS</b>: única fonte de verdade da aplicação (o bean
 * {@code corsConfigurationSource} é consumido pelo filter chain e cobre todas
 * as requisições, incluindo preflight). Origem exata + credenciais para o
 * cookie de refresh funcionar de localhost:3000. Headers explícitos — nunca
 * {@code "*"} junto de {@code allowCredentials(true)}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Origem exata do frontend em dev — nunca "*" com allowCredentials(true). */
    static final String DEFAULT_FRONTEND_ORIGIN = "http://localhost:3000";

    /**
     * Declared here instead of {@code @Component} on purpose: {@code @WebMvcTest}
     * scans every {@link org.springframework.core.convert.converter.Converter},
     * and a component-level bean would drag {@code UserRepository} (JPA) into
     * MVC slices that do not boot JPA. As an inner {@code @Bean} it exists only
     * where the security chain exists.
     */
    @Bean
    UserJwtAuthenticationConverter userJwtAuthenticationConverter(UserRepository userRepository) {
        return new UserJwtAuthenticationConverter(userRepository);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserJwtAuthenticationConverter jwtAuthenticationConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // Double-submit: cookie XSRF-TOKEN não-HttpOnly + resolução do header cru.
                .csrf(csrf -> csrf.spa())
                // Preflight OPTIONS processado pelo CorsFilter antes da autorização.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * Única fonte de verdade de CORS (filter chain). allowCredentials(true)
     * exige origem exata — http://localhost:3000 — e é o que permite o
     * navegador enviar/receber os cookies refresh_token e XSRF-TOKEN via
     * fetch credenciado.
     *
     * <p>O header {@code X-XSRF-TOKEN} precisa estar declarado: sem isso o
     * preflight bloqueia o double-submit CSRF cross-origin.</p>
     *
     * <p>{@code app.cors.allowed-origins} aceita origens separadas por vírgula.
     * {@code "*"} não é aceito aqui: combinado com allowCredentials(true) ele
     * permitiria que qualquer site lesse o cookie de refresh — por isso
     * {@link #parseAllowedOrigins(String)} valida no <b>startup</b> e derruba a
     * aplicação com {@link IllegalStateException} em vez de deixar o Spring
     * rejeitar em runtime.</p>
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:" + DEFAULT_FRONTEND_ORIGIN + "}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Content-Type", "Authorization", "X-XSRF-TOKEN", "X-Organization-Id", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Parsing da lista {@code app.cors.allowed-origins} (separada por vírgula):
     * trim + descarte de entradas vazias e <b>fail-fast</b> de wildcard.
     *
     * <p>A validação roda na criação do bean — ou seja, no <b>startup</b> — e
     * não em runtime: com {@code allowCredentials(true)} uma origem {@code "*"}
     * é uma configuração inválida que só estouraria na primeira requisição
     * preflight cross-origin (e, se passasse, permitiria que qualquer site
     * lesse o cookie de refresh).</p>
     */
    static List<String> parseAllowedOrigins(String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        validateOrigins(origins);
        return origins;
    }

    /**
     * Wildcard é incompatível com credenciais: {@code CorsConfiguration} só
     * rejeita isso em runtime ({@code validateAllowCredentials}), então aqui a
     * regra é explícita para falhar no startup com mensagem acionável.
     *
     * @throws IllegalStateException se alguma origem contiver {@code *}
     */
    static void validateOrigins(List<String> origins) {
        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalStateException(
                        "Invalid CORS configuration: origin \"" + origin + "\" contains a wildcard and is"
                                + " incompatible with allowCredentials=true. List exact origins in"
                                + " app.cors.allowed-origins (e.g. http://localhost:3000).");
            }
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 401 no shape ErrorResponse padrão da API (não o corpo default do Spring Security). */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) ->
                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", "Authentication required", request.getRequestURI());
    }

    /** 403 no mesmo shape padrão (CSRF ausente/inválido ou acesso proibido). */
    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                        "FORBIDDEN", "Access denied", request.getRequestURI());
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message,
            String path) throws java.io.IOException {
        ErrorResponse body = new ErrorResponse(code, message, Instant.now(), path);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
