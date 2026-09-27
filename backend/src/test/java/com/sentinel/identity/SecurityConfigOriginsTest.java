package com.sentinel.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Fail-fast das origens CORS (Prompt 05, achado P2): a validação roda na
 * criação do bean — ou seja, no startup — e não em runtime.
 *
 * <p>Sem Spring context completo: {@code parseAllowedOrigins} é estático,
 * testável isoladamente, e é exatamente o caminho percorrido pelo bean
 * {@code corsConfigurationSource}.</p>
 */
class SecurityConfigOriginsTest {

    @Test
    void exactFrontendOriginIsAccepted() {
        List<String> origins = SecurityConfig.parseAllowedOrigins("http://localhost:3000");

        assertThat(origins).containsExactly("http://localhost:3000");
    }

    @Test
    void multipleExactOriginsAreAccepted() {
        List<String> origins =
                SecurityConfig.parseAllowedOrigins("http://localhost:3000, https://sentinel.example");

        assertThat(origins).containsExactly("http://localhost:3000", "https://sentinel.example");
    }

    @Test
    void wildcardOriginIsRejectedWithActionableMessage() {
        // "*" + allowCredentials(true) deixaria qualquer site ler o cookie de
        // refresh — a aplicação não deve subir com isso configurado
        assertThatThrownBy(() -> SecurityConfig.parseAllowedOrigins("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard")
                .hasMessageContaining("allowCredentials=true")
                .hasMessageContaining("app.cors.allowed-origins");
    }

    @Test
    void wildcardAmongMultipleOriginsIsRejected() {
        assertThatThrownBy(() -> SecurityConfig.parseAllowedOrigins(
                        "http://localhost:3000, *, https://sentinel.example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowCredentials=true");
    }

    @Test
    void embeddedWildcardPatternIsRejected() {
        // padrão com curinga também é inválido em allowedOrigins (o Spring só
        // aceita padrão via allowedOriginPatterns) — aqui falha no startup em
        // vez de virar uma origem silenciosamente inerte
        assertThatThrownBy(() -> SecurityConfig.parseAllowedOrigins("http://*.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowCredentials=true");
    }

    @Test
    void blankAndWhitespaceEntriesAreFiltered() {
        assertThat(SecurityConfig.parseAllowedOrigins(" ,  http://localhost:3000 ,, "))
                .containsExactly("http://localhost:3000");
    }

    @Test
    void emptyConfigurationYieldsNoOriginsInsteadOfFailing() {
        // vazio = nenhuma origem cross-origin permitida (mantém o comportamento
        // anterior: só o wildcard é rejeitado, não a ausência de origens)
        assertThat(SecurityConfig.parseAllowedOrigins("")).isEmpty();
        assertThat(SecurityConfig.parseAllowedOrigins("   ")).isEmpty();
        assertThat(SecurityConfig.parseAllowedOrigins(",")).isEmpty();
    }
}
