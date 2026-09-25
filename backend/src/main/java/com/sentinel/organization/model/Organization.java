package com.sentinel.organization.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Organização = tenant da plataforma (Prompt 04).
 *
 * <p>Uma organização é o boundary de isolamento: todos os recursos futuros
 * (projetos, serviços, monitors, alertas, incidentes, audit) pertencem a
 * exatamente uma organização, e o acesso a eles passa pela Membership do
 * usuário nesta entidade.</p>
 *
 * <p>Padrão de entidade alinhado ao de {@code User}: UUID gerado pela
 * aplicação em {@code @PrePersist} (a migration V4 portanto NÃO declara
 * DEFAULT), {@code createdAt}/{@code updatedAt} mantidos pelos callbacks de
 * vida JPA e construtor protegido para o JPA + construtor público de domínio.</p>
 *
 * <p>A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code OrganizationResponse}).</p>
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Teto de 128 = @Size(max=128) da API e VARCHAR(128) da migration V4. */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Organization() {
        // JPA
    }

    public Organization(String name) {
        this.name = name;
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

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
