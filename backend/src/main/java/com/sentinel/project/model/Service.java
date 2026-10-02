package com.sentinel.project.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
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
 * {@link ServiceStatus#UNKNOWN} no construtor de domínio e não é aceito em
 * payload algum — nenhum endpoint cria ou edita status. A ÚNICA transição
 * vem do motor de monitoramento (Prompt 10): o
 * {@code OperationalStateEvaluator} chama {@link #setStatusInternal} sob
 * lock pessimista da linha, apenas quando o recálculo muda o valor. O setter
 * é público porque o avaliador vive em outro pacote
 * ({@code com.sentinel.monitoring}); a restrição é de quem o chama, não de
 * visibilidade Java.</p>
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
     * Estado operacional: nasce {@link ServiceStatus#UNKNOWN} e só muda pelo
     * {@link #setStatusInternal} (ver javadoc da classe). {@code length = 20}
     * espelha {@code VARCHAR(20)} + CHECK da migration V6.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceStatus status;

    /**
     * QUANDO o {@link #status} atual começou a valer (Prompt 13, migration
     * V10). Sem isto a regra {@code SERVICE_STATUS} só saberia <em>que</em>
     * estado o serviço está, nunca <em>há quanto tempo</em> — e o falso
     * "UNAVAILABLE há 5 minutos" é justamente o que o Alert Engine elimina.
     *
     * <p>Anulável de propósito: {@code null} = "nenhuma transição registrada"
     * (serviço recém-criado ainda {@code UNKNOWN}), diferente de "mudou
     * agora". Escrito pelo MESMO {@link #setStatusInternal}, portanto escritor
     * único = {@code OperationalStateEvaluator} (contrato preservado).</p>
     */
    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

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

    // --- mutações (ServiceService; exceto status, só o evaluator) ---------

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

    /**
     * Transição de estado operacional — chamado EXCLUSIVAMENTE pelo
     * {@code OperationalStateEvaluator} do motor de monitoramento (Prompt
     * 10), já sob lock pessimista da linha e apenas quando o valor muda.
     *
     * <p>Não é setter de API: nenhum endpoint aceita status em payload, o
     * construtor de domínio continua nascendo {@link ServiceStatus#UNKNOWN} e
     * a ativação/desativação do serviço não mexe aqui.</p>
     *
     * @param status novo estado (nunca {@code null})
     * @param when   instante da transição — vira {@code statusChangedAt},
     *               o eixo temporal que a regra {@code SERVICE_STATUS}
     *               compara com a janela (Prompt 13)
     */
    public void setStatusInternal(ServiceStatus status, Instant when) {
        this.status = Objects.requireNonNull(status, "status");
        this.statusChangedAt = Objects.requireNonNull(when, "when");
    }

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

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
