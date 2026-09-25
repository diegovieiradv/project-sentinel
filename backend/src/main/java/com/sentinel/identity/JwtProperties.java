package com.sentinel.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração de JWT e do cookie de refresh (Prompt 03).
 *
 * <p>Propriedades: {@code jwt.secret}, {@code jwt.access-ttl-seconds},
 * {@code jwt.refresh-ttl-days} e {@code jwt.cookie.*} (name, path, secure,
 * same-site). Os defaults no constructor compacto são apenas uma rede de
 * segurança — o {@code application.yml} explícito é a fonte de verdade.</p>
 *
 * <p>O secret nunca é logado. A resolução (geração em dev, fail-fast em prod,
 * tamanho mínimo) fica em {@link JwtConfiguration}.</p>
 */
@ConfigurationProperties("jwt")
public record JwtProperties(
        String secret,
        long accessTtlSeconds,
        long refreshTtlDays,
        Cookie cookie) {

    /** Cookie que carrega o refresh token bruto (HttpOnly, Path=/api/v1/auth). */
    public static final String DEFAULT_COOKIE_NAME = "refresh_token";

    public static final String DEFAULT_COOKIE_PATH = "/api/v1/auth";

    /** Access token: 15 minutos. */
    public static final long DEFAULT_ACCESS_TTL_SECONDS = 900L;

    /** Refresh token: 7 dias. */
    public static final long DEFAULT_REFRESH_TTL_DAYS = 7L;

    public JwtProperties {
        if (accessTtlSeconds <= 0) {
            accessTtlSeconds = DEFAULT_ACCESS_TTL_SECONDS;
        }
        if (refreshTtlDays <= 0) {
            refreshTtlDays = DEFAULT_REFRESH_TTL_DAYS;
        }
        if (cookie == null) {
            cookie = new Cookie(null, null, false, null);
        }
    }

    /** Tempo de vida do refresh cookie, em segundos (para o atributo Max-Age). */
    public long refreshTtlSeconds() {
        return refreshTtlDays * 24L * 60L * 60L;
    }

    /**
     * Atributos do cookie de refresh. {@code secure=false} apenas em dev/test;
     * o perfil prod força {@code secure=true}.
     */
    public record Cookie(String name, String path, boolean secure, String sameSite) {

        public Cookie {
            if (name == null || name.isBlank()) {
                name = DEFAULT_COOKIE_NAME;
            }
            if (path == null || path.isBlank()) {
                path = DEFAULT_COOKIE_PATH;
            }
            if (sameSite == null || sameSite.isBlank()) {
                sameSite = "Strict";
            }
        }
    }
}
