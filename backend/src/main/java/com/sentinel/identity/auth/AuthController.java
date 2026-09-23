package com.sentinel.identity.auth;

import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de autenticação do Prompt 02.
 *
 * Auth transitória por sessão HTTP server-side (cookie JSESSIONID).
 * O login autentica no SecurityContext e grava a sessão via
 * SecurityContextRepository explícito (o SecurityContextHolderFilter do
 * Spring Security 7 apenas carrega/limpa — não persiste por conta própria).
 * A troca para JWT/refresh (Prompt 03) concentra-se aqui.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String TOKEN_TYPE_SESSION = "session";

    private final UserService userService;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(UserService userService, SecurityContextRepository securityContextRepository) {
        this.userService = userService;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * POST /api/v1/auth/register → 201 {user:{id,name,email}}.
     * Não inicia sessão — o login é o único ponto que autentica (Prompt 02).
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(user, null));
    }

    /** POST /api/v1/auth/login → 200 {user:{id,name,email}, tokenType:"session"} + sessão. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        User user = userService.authenticate(request.email(), request.password());
        establishSession(user, httpRequest, httpResponse);

        return ResponseEntity.ok(new AuthResponse(UserResponse.from(user), TOKEN_TYPE_SESSION));
    }

    /** GET /api/v1/auth/me → 200 UserResponse; 401 sem sessão válida. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Object principal) {
        return userService.resolveCurrentUser(principal);
    }

    private void establishSession(User user, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
