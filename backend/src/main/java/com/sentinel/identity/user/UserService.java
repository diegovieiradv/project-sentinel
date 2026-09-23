package com.sentinel.identity.user;

import java.util.Locale;
import java.util.regex.Pattern;

import com.sentinel.error.ApiException;
import com.sentinel.identity.auth.RegisterRequest;
import com.sentinel.identity.auth.UserResponse;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de identity do Prompt 02: registro, verificação de credenciais e
 * resolução do principal autenticado.
 *
 * Política de senha (MVP, documentada em docs/identity.md):
 * mínimo 8 caracteres, ao menos 1 letra e ao menos 1 dígito.
 */
@Service
public class UserService implements UserDetailsService {

    /** Política MVP de senha: min 8 chars, ≥1 letra, ≥1 dígito. */
    static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    /** Mensagem da política de senha (contrato público da API — doc e testes). */
    public static final String PASSWORD_POLICY_MESSAGE =
            "Password must be at least 8 characters and include at least one letter and one digit";

    /**
     * Mensagem única e genérica para login inválido — idêntica para usuário
     * inexistente, senha errada e conta inativa (não vaza a existência da conta).
     * Contrato público da API — doc e testes.
     */
    public static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Normaliza o e-mail (trim + lowercase) — usada em TODA operação antes de
     * consultar/persistir, garantindo o mesmo formato do unique do banco.
     */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Cria o usuário. Bean validation roda no controller; aqui ficam a política
     * de senha, a normalização do e-mail e o check de duplicidade.
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        validatePasswordPolicy(request.password());
        if (userRepository.existsByEmail(email)) {
            throw emailAlreadyExists();
        }

        User user = new User(request.name().trim(), email,
                passwordEncoder.encode(request.password()), true);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // corrida entre o check acima e o insert: unique do e-mail vence
            throw emailAlreadyExists();
        }
        return UserResponse.from(user);
    }

    /**
     * Verifica credenciais. Falha (usuário inexistente, senha errada ou
     * conta inactive) → 401 com mensagem genérica idêntica nos três casos.
     * Em sucesso retorna o usuário; a criação/persistência da sessão é
     * responsabilidade do controller (camada HTTP).
     */
    public User authenticate(String rawEmail, String rawPassword) {
        String email = normalizeEmail(rawEmail);
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw invalidCredentials();
        }
        return user;
    }

    /**
     * Resolve o usuário a partir do principal da sessão.
     * Esperado: a própria entidade {@link User} (fluxo de login real);
     * fallback: principal String (ex.: testes com @WithMockUser) tratado
     * como e-mail/username.
     */
    public UserResponse resolveCurrentUser(Object principal) {
        if (principal == null) {
            throw unauthorized();
        }
        if (principal instanceof User user) {
            return UserResponse.from(user);
        }
        return userRepository.findByEmail(normalizeEmail(principal.toString()))
                .map(UserResponse::from)
                .orElseThrow(UserService::unauthorized);
    }

    /** UserDetailsService — username = e-mail normalizado. */
    @Override
    public User loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmail(normalizeEmail(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private static ApiException emailAlreadyExists() {
        return new ApiException("EMAIL_ALREADY_EXISTS", "Email is already registered", HttpStatus.CONFLICT);
    }

    private static ApiException invalidCredentials() {
        return new ApiException("INVALID_CREDENTIALS", INVALID_CREDENTIALS_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    private static ApiException unauthorized() {
        return new ApiException("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED);
    }

    private static void validatePasswordPolicy(String password) {
        if (password == null || !PASSWORD_POLICY.matcher(password).matches()) {
            throw new ApiException("INVALID_PASSWORD", PASSWORD_POLICY_MESSAGE, HttpStatus.BAD_REQUEST);
        }
    }
}
