package com.sentinel.identity.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de usuários. O e-mail chega aqui já normalizado
 * (trim + lowercase) pelo {@link UserService}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
