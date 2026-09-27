package com.sentinel.project.model;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.sentinel.organization.model.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Projeto de uma organização (Prompt 06) — o primeiro recurso de domínio
 * enraizado no tenant: Organization 1 → N Project.
 *
 * <p><b>Unicidade por organização:</b> {@code name_normalized} (trim +
 * lowercase, {@link Locale#ROOT}) é persistido e tem
 * {@code UNIQUE (organization_id, name_normalized)} na migration V5 — o mesmo
 * nome normalizado não existe duas vezes na mesma organização, ativo OU
 * inativo. Organizações diferentes podem repetir. A aplicação faz um
 * pré-check rápido (409 imediato) e o banco é a última linha de defesa sob
 * corrida (também 409, nunca SQL vazando).</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@code Organization}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V5 portanto NÃO declara
 * DEFAULT), {@code createdAt}/{@code updatedAt} mantidos pelos callbacks de
 * vida JPA e construtor protegido para o JPA + construtor público de domínio.
 * A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code ProjectResponse}).</p>
 *
 * <p><b>Observação sobre {@code nameNormalized}:</b> ao contrário de
 * {@code createdAt}, a coluna é <i>atualizável</i> porque o {@code PATCH} de
 * renomeação precisa persistir a nova chave de unicidade — com
 * {@code updatable=false} o Hibernate a excluiria do UPDATE e deixaria a linha
 * com a normalização antiga (unicidade e busca por nome quebradas em silêncio).
 * O recálculo acontece em {@link #rename(String)}, único caminho de escrita do
 * nome após a criação.</p>
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code Membership}): o
     * {@code TenantInterceptor} resolve a membership fora de transação e a
     * resposta expõe {@code organizationId}; um proxy LAZY explodiria em
     * {@code LazyInitializationException} fora da sessão JPA.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Teto de 120 = @Size(max=120) da API e VARCHAR(120) da migration V5. */
    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    /** Nasce {@code true}; é desativado pelo endpoint de status (soft-delete). */
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Chave de unicidade por tenant: {@link #normalizeName(String)}. */
    @Column(name = "name_normalized", nullable = false, length = 120)
    private String nameNormalized;

    protected Project() {
        // JPA
    }

    /**
     * Projeto de domínio: nasce ativo, com a normalização calculada no
     * próprio construtor (fonte única da regra trim + lowercase).
     *
     * @param organization organização dona (tenant) do projeto
     * @param name         nome já validado e trimado pela aplicação
     * @param description  descrição opcional (pode ser {@code null})
     */
    public Project(Organization organization, String name, String description) {
        this.organization = organization;
        this.name = name;
        this.description = description;
        this.active = true;
        this.nameNormalized = normalizeName(name);
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

    // --- normalização ------------------------------------------------------

    /**
     * Chave de unicidade/busca: trim + lowercase {@link Locale#ROOT} (sem
     * regras de caixa do locale do servidor — "TRABALHO".toLowerCase() em
     * turco seria "trabalho", não "iş"). É o MESMO valor persistido em
     * {@code name_normalized} e usado no pré-check do service.
     */
    public static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // --- mutações (somente pelo service) -----------------------------------

    /** Renomeia e recalcula a chave de unicidade — único caminho pós-criação. */
    public void rename(String name) {
        this.name = name;
        this.nameNormalized = normalizeName(name);
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Ativação/desativação idempotente — só o service decide se há escrita. */
    public void setActive(boolean active) {
        this.active = active;
    }

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
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

    public String getNameNormalized() {
        return nameNormalized;
    }
}
