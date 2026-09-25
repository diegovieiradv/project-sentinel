package com.sentinel.identity.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import com.sentinel.error.ApiException;
import com.sentinel.identity.JwtProperties;
import com.sentinel.identity.user.User;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token opaco: emissão, rotação atômica, reuso e revogação (Prompt 03).
 *
 * <p><b>Formato do token</b>: 32 bytes de {@link SecureRandom} em Base64URL sem
 * padding (~43 chars). O valor bruto nunca é persistido nem logado; só o
 * SHA-256 hex ({@code token_hash}, 64 chars) vai para o banco.</p>
 *
 * <p><b>Rotação</b>: um UPDATE condicional revoga o token atual e, só se ele
 * era o vencedor da corrida (1 linha afetada), insere o substituto. Dois
 * refreshes simultâneos com o mesmo token → um ganha, o outro recebe 401 e
 * precisa logar de novo (sem grace-period; decisão registrada em
 * docs/identity.md).</p>
 *
 * <p><b>Reuso</b>: apresentar um token já revogado revoga TODOS os tokens do
 * usuário — é o sinal clássico de token vazado/roubado. O hash inexistente é
 * apenas inválido.</p>
 */
@Service
public class RefreshTokenService {

    public static final String INVALID_CODE = "REFRESH_TOKEN_INVALID";
    public static final String EXPIRED_CODE = "REFRESH_TOKEN_EXPIRED";
    public static final String REVOKED_CODE = "REFRESH_TOKEN_REVOKED";

    public static final String INVALID_MESSAGE = "Invalid refresh token";
    public static final String EXPIRED_MESSAGE = "Refresh token expired";
    public static final String REVOKED_MESSAGE = "Refresh token revoked";

    /** 32 bytes → ~43 chars Base64URL. */
    static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.ttl = Duration.ofDays(jwtProperties.refreshTtlDays());
    }

    /** Resultado de uma rotação bem-sucedida. */
    public record Rotation(User user, String rawToken) {
    }

    /**
     * Emite um novo refresh token para o usuário (login).
     *
     * @return o token bruto — devolvido ao cliente UMA vez, via cookie HttpOnly.
     */
    @Transactional
    public String issue(User user) {
        Instant now = Instant.now();
        return insert(user, now, now.plus(ttl));
    }

    /**
     * Rotaciona o refresh token apresentado.
     *
     * <p>{@code noRollbackFor = ApiException}: o ramo de REUSO (token já
     * revogado reapresentado) executa {@code revokeAllActiveForUser} e só
     * depois sinaliza 401. Sem essa exceção de rollback a transação seria
     * desfeita, o revogamento de todos os tokens seria anulado e o atacante
     * continuaria com a sessão ativa — exatamente o oposto do que a regra de
     * segurança exige. Os demais {@code ApiException} deste método são
     * lançados antes de qualquer escrita, então commit-los é inócuo; falhas de
     * persistência não são {@code ApiException} e continuam com rollback.</p>
     *
     * @param rawToken token bruto lido do cookie
     * @return usuário dono e novo token bruto (já persistido)
     * @throws ApiException 401 com REFRESH_TOKEN_INVALID / _EXPIRED / _REVOKED
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid();
        }
        Instant now = Instant.now();
        String hash = sha256Hex(rawToken);

        int revokedRows = refreshTokenRepository.revokeIfActive(hash, now);
        if (revokedRows == 1) {
            RefreshToken previous = refreshTokenRepository.findByTokenHash(hash)
                    .orElseThrow(RefreshTokenService::invalid);
            return new Rotation(previous.getUser(), insert(previous.getUser(), now, now.plus(ttl)));
        }

        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(RefreshTokenService::invalid);
        if (existing.isRevoked()) {
            // reuso de token revogado → possível comprometimento: derruba a sessão inteira
            refreshTokenRepository.revokeAllActiveForUser(existing.getUser().getId(), now);
            throw revoked();
        }
        if (!existing.getExpiresAt().isAfter(now)) {
            throw expired();
        }
        // ativo e não expirado, mas o UPDATE condicional não casou: corrida perdida
        throw revoked();
    }

    /**
     * Revoga o token apresentado (logout). Deliberadamente idempotente e
     * silencioso: token inexistente, já revogado ou expirado resultam no mesmo
     * 200 do logout — o cookie é sempre limpo pelo controller.
     *
     * @return true quando esta chamada foi a que revogou o token
     */
    @Transactional
    public boolean revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return refreshTokenRepository.revokeIfActive(sha256Hex(rawToken), Instant.now()) == 1;
    }

    private String insert(User user, Instant issuedAt, Instant expiresAt) {
        String rawToken = generateToken();
        refreshTokenRepository.saveAndFlush(new RefreshToken(user, sha256Hex(rawToken), issuedAt, expiresAt));
        return rawToken;
    }

    /** 32 bytes aleatórios → Base64URL sem padding. Nunca logado, nunca persistido. */
    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 → hex minúsculo (64 chars). */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", ex);
        }
    }

    private static ApiException invalid() {
        return new ApiException(INVALID_CODE, INVALID_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    private static ApiException expired() {
        return new ApiException(EXPIRED_CODE, EXPIRED_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    private static ApiException revoked() {
        return new ApiException(REVOKED_CODE, REVOKED_MESSAGE, HttpStatus.UNAUTHORIZED);
    }
}
