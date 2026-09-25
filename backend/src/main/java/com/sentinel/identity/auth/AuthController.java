package com.sentinel.identity.auth;

import java.util.Map;

import com.sentinel.identity.JwtProperties;
import com.sentinel.identity.token.RefreshTokenService;
import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de autenticação do Prompt 03 — stateless.
 *
 * <p>Nenhuma sessão server-side: o login devolve um access token JWT (corpo JSON)
 * e grava o refresh token em cookie {@code HttpOnly + SameSite=Strict + Path=/api/v1/auth}.
 * O {@code /refresh} rotaciona o refresh e emite um novo access; o {@code /logout}
 * revoga o refresh apresentado e limpa o cookie (idempotente).</p>
 *
 * <p>O Spring Security carrega o principal a partir do Bearer via o converter do
 * {@code oauth2ResourceServer} — o controller lê {@code @AuthenticationPrincipal}
 * exatamente como fazia com a sessão.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;

    public AuthController(
            UserService userService,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService,
            JwtProperties jwtProperties) {
        this.userService = userService;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * POST /api/v1/auth/register → 201 {user:{id,name,email}}.
     * Não emite token: o login é o único ponto que autentica.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthResponse.registered(userService.register(request)));
    }

    /** POST /api/v1/auth/login → 200 {user, accessToken, tokenType} + cookie refresh. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse httpResponse) {
        User user = userService.authenticate(request.email(), request.password());
        writeRefreshCookie(httpResponse, refreshTokenService.issue(user));

        return ResponseEntity.ok(AuthResponse.authenticated(UserResponse.from(user), accessTokenService.issue(user)));
    }

    /**
     * POST /api/v1/auth/refresh → 200 {user, accessToken, tokenType} + cookie novo.
     *
     * <p>Rotaciona o refresh token; o anterior deixa de valer imediatamente.
     * Falhas são 401 com código específico: REFRESH_TOKEN_INVALID,
     * REFRESH_TOKEN_EXPIRED ou REFRESH_TOKEN_REVOKED.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(readRefreshCookie(httpRequest));
        writeRefreshCookie(httpResponse, rotation.rawToken());

        return ResponseEntity.ok(
                AuthResponse.authenticated(UserResponse.from(rotation.user()), accessTokenService.issue(rotation.user())));
    }

    /**
     * POST /api/v1/auth/logout → 200 {"message":"Logged out"}.
     *
     * <p>Idempotente por contrato: revoga apenas o refresh apresentado (não
     * derruba os demais aparelhos) e sempre limpa o cookie — mesmo quando o
     * token já não existe ou já foi revogado.</p>
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        refreshTokenService.revoke(readRefreshCookie(httpRequest));
        clearRefreshCookie(httpResponse);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /** GET /api/v1/auth/me → 200 UserResponse (Bearer); 401 sem/inválido. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Object principal) {
        return userService.resolveCurrentUser(principal);
    }

    // --- cookie de refresh ------------------------------------------------

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = jwtProperties.cookie().name();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeRefreshCookie(HttpServletResponse response, String rawToken) {
        response.addCookie(refreshCookie(rawToken, jwtProperties.refreshTtlSeconds()));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        // Mesmo nome/path/atributos do cookie emitido — só o Max-Age=0 o apaga.
        response.addCookie(refreshCookie("", 0));
    }

    /**
     * Cookie de refresh: HttpOnly (invisível para o JS), Path restrito ao
     * namespace de auth (cobre /refresh e /logout), SameSite=Strict e Secure
     * quando configurado (prod força true).
     */
    private Cookie refreshCookie(String value, long maxAgeSeconds) {
        JwtProperties.Cookie config = jwtProperties.cookie();
        Cookie cookie = new Cookie(config.name(), value);
        cookie.setHttpOnly(true);
        cookie.setSecure(config.secure());
        cookie.setPath(config.path());
        cookie.setMaxAge((int) maxAgeSeconds);
        cookie.setAttribute("SameSite", config.sameSite());
        return cookie;
    }
}
