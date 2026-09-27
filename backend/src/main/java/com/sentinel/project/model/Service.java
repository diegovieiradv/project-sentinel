package com.sentinel.project.model;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

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
 * Serviço de um projeto (Prompt 07) — segundo nível da hierarquia
 * Organization → Project → Service.
 *
 * <p><b>Unicidade por projeto:</b> {@code name_normalized} (trim + lowercase,
 * {@link Locale#ROOT}) é persistido e tem
 * {@code UNIQUE (project_id, name_normalized)} na migration V6 — o mesmo nome
 * normalizado não existe duas vezes no mesmo projeto, ativo OU inativo.
 * Projetos (e portanto organizações) diferentes podem repetir nome. A
 * aplicação faz um pré-check rápido (409 imediato) e o banco é a última linha
 * de defesa sob corrida (também 409, nunca SQL vazando).</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@code Project}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V6 portanto NÃO declara
 * {@code DEFAULT}), {@code createdAt}/{@code updatedAt} mantidos pelos
 * callbacks de vida JPA, construtor protegido para o JPA + construtor público
 * de domínio. A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code ServiceResponse}).</p>
 *
 * <p><b>{@code status} é propriedade de servidor:</b> nasce
 * {@link ServiceStatus#UNKNOWN} no construtor de domínio e não tem setter —
 * nenhum endpoint deste prompt o altera (a rota de ativação mexe apenas em
 * {@code active}). Quando o monitoramento existir (prompt futuro) a transição
 * de status entrará como caminho de domínio explícito, não como campo de payload.</p>
 *
 * <p><b>Nome da classe:</b> {@code Service} colide com a anotação
 * {@code @Service} do Spring nos classes que precisam das DUAS. O arranjo
 * escolhido está em {@code ServiceService}: a entidade é importada normalmente
 * (é a que aparece em várias assinaturas) e o estereótipo do Spring é escrito
 * uma única vez com o nome completo ({@code @org.springframework.stereotype.Service}).</p>
 */
@Entity
@Table(name = "services")
public class Service {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code Project.organization}):
     * a resposta expõe {@code organizationId}/{@code projectId} via projeto e o
     * lookup fora de transação explodiria em {@code LazyInitializationException}
     * com um proxy LAZY.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Teto de 120 = @Size(max=120) da API e VARCHAR(120) da migration V6. */
    @Column(nullable = false, length = 120)
    private String name;

    /** Chave de unicidade por projeto: {@link #normalizeName(String)}. */
    @Column(name = "name_normalized", nullable = false, length = 120)
    private String nameNormalized;

    @Column(length = 1000)
    private String description;

    /** Nasce {@code true}; é desativado pela rota de ativação (soft-delete). */
    @Column(nullable = false)
    private boolean active;

    /**
     * Estado operacional: nasce {@link ServiceStatus#UNKNOWN} e NÃO tem
     * setter público — este prompt não altera status (ver javadoc da classe).
     * {@code length = 20} espelha {@code VARCHAR(20)} + CHECK da migration V6.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Service() {
        // JPA
    }

    /**
     * Serviço de domínio: nasce ativo e com status {@code UNKNOWN} (nada foi
     * observado ainda), com a normalização calculada no próprio construtor
     * (fonte única da regra trim + lowercase).
     *
     * @param project     projeto dono (já resolvido e validado como ativo)
     * @param name        nome já validado e trimado pela aplicação
     * @param description descrição opcional (pode ser {@code null})
     */
    public Service(Project project, String name, String description) {
        this.project = project;
        this.name = name;
        this.description = description;
        this.active = true;
        this.status = ServiceStatus.UNKNOWN;
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
     * regras de caixa do locale do servidor). É o MESMO valor persistido em
     * {@code name_normalized} e usado no pré-check do service — e também o
     * alvo da busca por nome da listagem (case-insensitive por construção).
     */
    public static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // --- mutações (somente pelo ServiceService) ----------------------------

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

    // Não existe setStatus: o status é de domínio/servidor e neste prompt é
    // imutável após a criação (Prompt 08+ adicionará transição explícita).

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
