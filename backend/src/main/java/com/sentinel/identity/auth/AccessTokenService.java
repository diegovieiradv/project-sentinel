package com.sentinel.identity.auth;

import java.time.Instant;

import com.sentinel.identity.JwtProperties;
import com.sentinel.identity.user.User;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emite o access token HS256 do Prompt 03.
 *
 * <p>Claims limitadas ao mínimo: {@code sub} (UUID do usuário em string),
 * {@code exp} e {@code iat}. Sem issuer, sem audience, sem roles — nada disso
 * existe ainda no produto e claim inventado é contrato que depois custa caro
 * para remover.</p>
 *
 * <p>TTL de {@code jwt.access-ttl-seconds} (default 900s = 15min). O refresh
 * é a renovação; o access token nunca é persistido no servidor.</p>
 */
@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public AccessTokenService(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    /** Assina o access token do usuário. O valor é devolvido no corpo do JSON. */
    public String issue(User user) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(jwtProperties.accessTtlSeconds()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
