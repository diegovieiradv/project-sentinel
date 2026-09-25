package com.sentinel.organization.model;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.identity.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Vínculo de um usuário em uma organização, com o papel que ele exerce ali
 * (Prompt 04). É a unidade de autorização do multi-tenant: sem membership
 * resolvida não existe contexto de tenant.
 *
 * <p>UNIQUE (user_id, organization_id) na migration V4: um usuário tem no
 * máximo um papel por organização — a corrida de duplicidade vira
 * {@code DataIntegrityViolationException} e é traduzida em 409 no service.</p>
 *
 * <p><b>EAGER de propósito</b> (mesmo racional do {@code RefreshToken}): o
 * {@code TenantInterceptor} resolve a membership FORA de transação — a sessão
 * JPA do repositório já fechou quando o contexto de tenant é montado. Um
 * proxy LAZY de {@code organization}/{@code user} explodiria em
 * {@code LazyInitializationException} logo na primeira leitura. O volume por
 * organização é pequeno (lista de membros é admin-only), então o custo de
 * eager é aceitável e o benefício é ausência de bug de sessão.</p>
 */
@Entity
@Table(name = "memberships")
public class Membership {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** STRING: o valor persistido é o nome da enum (ADMIN/OPERATOR/VIEWER). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Membership() {
        // JPA
    }

    public Membership(User user, Organization organization, Role role) {
        this.user = user;
        this.organization = organization;
        this.role = role;
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

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Promoção/rebaixamento — só é chamado pelo service, já com o lock do tenant. */
    public void setRole(Role role) {
        this.role = role;
    }
}
