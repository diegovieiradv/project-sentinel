package com.sentinel.identity.auth;

import java.util.UUID;

import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Converte um access token JWT validado no principal de domínio {@link User}.
 *
 * <p>O converter padrão do Spring expõe o claim {@code sub} como uma String
 * principal; aqui o principal é a própria entidade, exatamente como era com a
 * sessão do Prompt 02 — assim {@code @AuthenticationPrincipal User} e
 * {@code UserService.resolveCurrentUser} continuam funcionando sem adaptação.</p>
 *
 * <p>Qualquer problema (claim ausente/inválido, usuário inexistente ou inativo)
 * vira 401 via {@link InvalidBearerTokenException}: um token assinado por nós
 * que não aponta para um usuário vivo não autoriza nada.</p>
 *
 * <p>Não é {@code @Component}: o bean é declarado em {@code SecurityConfig}
 * porque {@code @WebMvcTest} varre toda a classe que implementa
 * {@link Converter} e puxaria {@code UserRepository} para um contexto sem JPA.</p>
 */
public class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;

    public UserJwtAuthenticationConverter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = userRepository.findById(parseSubject(jwt))
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown or inactive token subject"));
        // credentials = null: nunca propagar o token bruto (nem para logs de debug)
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private static UUID parseSubject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        }
        catch (RuntimeException ex) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }
    }
}
