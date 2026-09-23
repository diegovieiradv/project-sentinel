package com.sentinel.identity.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Usuário do domínio identity (Prompt 02).
 *
 * Implementa {@link UserDetails} para que o principal da sessão Spring Security
 * seja a própria entidade de domínio — controllers leem via
 * {@code @AuthenticationPrincipal}/SecurityContextHolder e a troca futura por
 * JWT (Prompt 03) não exige repensar o principal.
 *
 * A entidade JPA NUNCA é exposta cru na API (contrato de saída: UserResponse);
 * getPassword/getPasswordHash são bloqueados no JSON por segurança extra.
 */
@Entity
@Table(name = "users")
public class User implements UserDetails {

    private static final long serialVersionUID = 1L;

    private static final GrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    /** Sempre normalizado (trim + lowercase) pelo UserService. */
    @Column(nullable = false, length = 255, unique = true)
    private String email;

    /** Hash BCrypt — nunca texto puro; nunca serializado em JSON. */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    public User(String name, String email, String passwordHash, boolean active) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // --- UserDetails (autenticação) -------------------------------------

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(ROLE_USER);
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** Usuário inativo (active=false) não pode autenticar. */
    @Override
    public boolean isEnabled() {
        return active;
    }

    // --- Getters de domínio ---------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Alteração de nome (mesmo pacote — teste de @PreUpdate). */
    void setName(String name) {
        this.name = name;
    }
}
