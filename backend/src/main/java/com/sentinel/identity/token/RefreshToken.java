package com.sentinel.identity.token;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.identity.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Refresh token opaco persistido pelo Prompt 03.
 *
 * <p>A entidade guarda APENAS o hash SHA-256 (hex) do token — o valor bruto
 * existe somente no cookie HttpOnly. {@code user_id} é ON DELETE CASCADE
 * (migration V3): remover o usuário remove seus tokens.</p>
 *
 * <p>{@code revoked} é o flag de rotação/reuso: um token deixa de valer assim
 * que é rotacionado (ou detectado como reutilizado) e permanece na tabela como
 * trilha de auditoria até o cleanup (fora do escopo deste prompt).</p>
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * EAGER de propósito: todo leitor desta tabela (rotação, reuso, logout)
     * precisa do dono imediatamente — e a sessão JPA já fechou quando o
     * controller monta o {@code AuthResponse}. Um proxy LAZY explodiria em
     * {@code LazyInitializationException} fora da transação.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex (64 chars) do token bruto — UNIQUE também no banco. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(User user, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke(Instant when) {
        this.revoked = true;
        this.revokedAt = when;
    }
}
