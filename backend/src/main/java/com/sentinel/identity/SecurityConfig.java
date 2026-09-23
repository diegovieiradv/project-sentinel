package com.sentinel.identity;

import java.time.Instant;
import java.util.List;

import com.sentinel.error.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;

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
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Segurança do Prompt 02 — Identity.
 *
 * <p><b>Auth transitória</b>: sessão HTTP server-side (cookie JSESSIONID,
 * SessionCreationPolicy.IF_REQUIRED). SEM JWT/refresh — chega no Prompt 03.
 * Controllers leem o principal via {@code @AuthenticationPrincipal}/
 * SecurityContextHolder para facilitar essa troca.</p>
 *
 * <p><b>CORS</b>: única fonte de verdade da aplicação (o bean
 * {@code corsConfigurationSource} é consumido pelo filter chain e cobre
 * todas as requisições, incluindo preflight). Origem exata + credenciais
 * para o cookie de sessão funcionar de localhost:3000.</p>
 *
 * <p><b>CSRF desabilitado</b> (justificativa): API JSON cross-origin com
 * CORS restrito à origem exata do frontend e cookies SameSite=Lax por
 * default dos navegadores modernos (bloqueia POST cross-site). Persistir
 * token de sessão exige handshake CSRF explícito — é exatamente o que o
 * Prompt 03 substitui ao migrar para JWT/refresh com_cookies dedicados.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Origem exata do frontend em dev — nunca "*" com allowCredentials(true). */
    static final String FRONTEND_ORIGIN = "http://localhost:3000";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // CSRF off: ver justificativa na javadoc desta classe.
                .csrf(csrf -> csrf.disable())
                // Preflight OPTIONS processado pelo CorsFilter antes da autorização.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context ->
                        context.securityContextRepository(securityContextRepository))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login")
                        .permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /**
     * Única fonte de verdade de CORS (filter chain). allowCredentials(true)
     * exige origem exata — http://localhost:3000 — e é o que permite o
     * navegador enviar/receber o cookie JSESSIONID via fetch credenciado.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(FRONTEND_ORIGIN));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Repositório de contexto explícito (request attribute + HTTP session),
     * compartilhado entre o filtro que carrega a sessão e o AuthController
     * que grava a sessão no login.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
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

    /** 403 no mesmo shape padrão (acesso de sessão autenticada a recurso proibido). */
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
