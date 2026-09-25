package com.sentinel.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Par de chaves HS256 do Prompt 03.
 *
 * <p><b>Regras do secret</b> (nunca logado, nunca versionado):</p>
 * <ul>
 *   <li>presente e ≥ 32 caracteres → usado como está (HS256 exige ≥ 256 bits);</li>
 *   <li>ausente em perfil não-prod → gerado no startup com {@link SecureRandom}
 *       e um WARNING dizendo que reinícios invalidam tokens emissor;</li>
 *   <li>ausente em prod → fail-fast ({@code JWT_SECRET é obrigatória});</li>
 *   <li>presente mas curto → fail-fast (a JVM/JCE também rejeitaria HMAC &lt; 256 bits).</li>
 * </ul>
 *
 * <p>Encoder e decoder derivam do MESMO bean de segredo, garantindo que uma
 * instância gerada em dev não assine com uma chave e valide com outra.</p>
 *
 * <p>O decoder valida apenas timestamp (não há issuer/audience): o validador
 * padrão de {@link NimbusJwtDecoder} usa clock skew de 60s.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    /** HS256 = HMAC-SHA256 → mínimo de 32 bytes. */
    static final int MIN_SECRET_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

    /**
     * Segredo HS256 já resolvido e validado. Holder interno para que encoder e
     * decoder compartilhem exatamente a mesma chave.
     */
    public record ResolvedJwtSecret(String value) {

        SecretKey toSecretKey() {
            return new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        }
    }

    @Bean
    ResolvedJwtSecret jwtSecret(JwtProperties properties, Environment environment) {
        String secret = properties.secret() == null ? "" : properties.secret().trim();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));

        if (secret.isEmpty()) {
            if (prod) {
                throw new IllegalStateException(
                        "JWT_SECRET é obrigatória no perfil prod (secret HS256 ausente)");
            }
            String generated = generateSecret();
            log.warn("JWT_SECRET ausente — gerando segredo HS256 efêmero para o perfil {}."
                    + " Tokens emitidos serão inválidos após reiniciar a aplicação."
                    + " Defina JWT_SECRET (>= {} caracteres) para sessões estáveis.",
                    profileName(environment), MIN_SECRET_LENGTH);
            return new ResolvedJwtSecret(generated);
        }

        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET deve ter pelo menos " + MIN_SECRET_LENGTH
                            + " caracteres (HS256 exige >= 256 bits); valor atual tem "
                            + secret.length());
        }
        return new ResolvedJwtSecret(secret);
    }

    @Bean
    JwtEncoder jwtEncoder(ResolvedJwtSecret secret) {
        return NimbusJwtEncoder.withSecretKey(secret.toSecretKey()).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(ResolvedJwtSecret secret) {
        return NimbusJwtDecoder.withSecretKey(secret.toSecretKey()).build();
    }

    /** 32 bytes aleatórios → Base64URL (~43 chars) — nunca um valor fraco hardcoded. */
    private static String generateSecret() {
        byte[] bytes = new byte[MIN_SECRET_LENGTH];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String profileName(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 ? String.join(",", active) : "default";
    }
}
