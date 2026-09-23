package com.sentinel.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * UserRepository contra PostgreSQL real (Testcontainers + Flyway V2):
 * lookup por e-mail, exists, unique do e-mail e @PreUpdate de updated_at.
 * Usa @SpringBootTest (o slice @DataJpaTest não está no classpath — ver pom).
 */
@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    private static User sample(String name, String email, String passwordHash) {
        return new User(name, email, passwordHash, true);
    }

    @Test
    void saveAndFindByEmailReturnsPersistedUser() {
        User saved = userRepository.saveAndFlush(
                sample("Alice", "alice@example.com", "$2a$10$hashplaceholderhashhashhashhashhashhashhashhashhashhas"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.isActive()).isTrue();

        Optional<User> found = userRepository.findByEmail("alice@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getName()).isEqualTo("Alice");
        assertThat(found.get().getPasswordHash()).isEqualTo(saved.getPasswordHash());
    }

    @Test
    void findByEmailReturnsEmptyForUnknownEmail() {
        userRepository.saveAndFlush(sample("Alice", "alice@example.com", "hash"));

        assertThat(userRepository.findByEmail("other@example.com")).isEmpty();
    }

    @Test
    void existsByEmailReflectsRegisteredEmail() {
        assertThat(userRepository.existsByEmail("bob@example.com")).isFalse();

        userRepository.saveAndFlush(sample("Bob", "bob@example.com", "hash"));

        assertThat(userRepository.existsByEmail("bob@example.com")).isTrue();
    }

    @Test
    void duplicateEmailViolatesUniqueConstraint() {
        userRepository.saveAndFlush(sample("First", "unique@example.com", "hash-1"));

        User duplicate = sample("Second", "unique@example.com", "hash-2");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preUpdateBumpsUpdatedAt() throws Exception {
        User saved = userRepository.saveAndFlush(sample("Carol", "carol@example.com", "hash"));
        Instant originalUpdatedAt = saved.getUpdatedAt();

        // backdate para garantir diferença visível (precisão do timestamptz)
        jdbcTemplate.update(
                "UPDATE users SET updated_at = updated_at - interval '1 hour' WHERE id = ?",
                saved.getId());

        User managed = userRepository.findById(saved.getId()).orElseThrow();
        managed.setName("Carol Updated");
        userRepository.saveAndFlush(managed);

        Instant reloadedUpdatedAt = userRepository.findById(saved.getId())
                .orElseThrow().getUpdatedAt();
        assertThat(reloadedUpdatedAt).isAfter(originalUpdatedAt.minusSeconds(120));
    }
}
