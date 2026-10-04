package com.sentinel.project.incident.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.model.Service;

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
import jakarta.persistence.Version;

/**
 * Incidente (Prompt 14) — registro MANUAL de um evento que pediu atenção
 * humana em UM serviço, sub-recurso na hierarquia
 * Organization → Project → Service → Incident.
 *
 * <p><b>Duas origens, uma máquina de estados:</b> o incidente nasce de um
 * humano ({@code POST .../incidents}, {@link IncidentSource#MANUAL}) ou da
 * automação Alert → Incident (Prompt 15, {@link IncidentSource#ALERT_AUTOMATION}
 * — flag opt-in por regra, correlação em com.sentinel.project.alert.automation).
 * Em AMBOS os casos quem dispara o alerta nunca decide sozinho: nenhuma
 * classe de {@code com.sentinel.alerting} referencia este pacote (travado por
 * {@code MonitoringEngineBoundaryTest.alertingPackageHasNoIncidentReferences})
 * e a coluna de vínculo continua invertida ({@code alerts.incident_id}),
 * escrita por UPDATE condicional — o Alert Engine não conhece a coluna nem o
 * pacote.</p>
 *
 * <p><b>Snapshot livre, timeline obrigatória:</b> os campos do incidente
 * são editáveis por ADMIN/OPERATOR enquanto não estiver RESOLVED, mas cada
 * edição efetiva vira uma linha em {@code incident_events} (append-only,
 * só-leitura na API). O estado evolui só pela máquina de estados de
 * {@link IncidentStatus} — não existe "status" genérico livre.</p>
 *
 * <p><b>{@code startedAt} é o eixo da listagem</b> (ordem fixa
 * {@code startedAt desc, id desc}) e é imutável depois do create — ele
 * responde "quando começou", não "quando alguém digitou"
 * ({@code createdAt} cobre isso). {@code resolvedAt} nasce {@code NULL} e é
 * preenchido UMA vez em {@link #transitionTo}, amarrado ao status pelo CHECK
 * {@code incidents_lifecycle_check} da migration V11.</p>
 *
 * <p><b>{@code createdByUserId}</b> guarda quem abriu (audit de primeira
 * linha, SEM notificação — autor não é "assignee" de nada). O relógio vem do
 * domínio ({@code com.sentinel.monitoring.Clock} injetado pelo service);
 * os callbacks JPA seguem o padrão do projeto ({@code Instant.now()} em
 * {@code createdAt}/{@code updatedAt}).</p>
 *
 * <p><b>{@code @Version}</b>: protege a linha contra escrita perdida quando
 * dois PATCHs chegam juntos (o handler global traduz
 * {@code OptimisticLockingFailureException} em {@code 409 CONFLICT}).</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@link com.sentinel.project.alert.model.AlertRule}:</b>
 * UUID gerado pela aplicação em {@code @PrePersist} (a V11 não declara
 * DEFAULT no id), construtor protegido para o JPA + construtor de domínio,
 * nunca exposta cru na API (contrato de saída: {@code IncidentResponse}).</p>
 */
@Entity
@Table(name = "incidents")
public class Incident {

    /** Título máximo: {@code VARCHAR(200)} da migration V11. */
    public static final int MAX_TITLE_LENGTH = 200;

    /** Descrição máxima: {@code VARCHAR(4000)} da migration V11. */
    public static final int MAX_DESCRIPTION_LENGTH = 4000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code Alert.service}): a
     * resposta expõe a hierarquia via serviço → projeto → organização.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    /** {@code null} = sem descrição (o service guarda {@code ""} como {@code null}). */
    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    /** {@code VARCHAR(10)} + CHECK {@code incidents_severity_check}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertSeverity severity;

    /** {@code VARCHAR(15)} + CHECK {@code incidents_status_check}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private IncidentStatus status;

    /**
     * Quem nasceu o incidente — {@code VARCHAR(20)} + CHECK
     * {@code incidents_source_check} da migration V12 ({@code MANUAL} |
     * {@code ALERT_AUTOMATION}). Nasce {@code MANUAL} no construtor de
     * domínio humano e é fixo depois: a origem é fato histórico, não estado
     * editável.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentSource source;

    /** Quando o incidente COMEÇOU — imutável após o create. */
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Preenchido UMA vez na transição para {@link IncidentStatus#RESOLVED}. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Quem abriu o incidente — audit de primeira linha. A V11 nasceu
     * {@code NOT NULL}; a V12 tornou a coluna anulável PARA a automação
     * (Prompt 15): {@link IncidentSource#ALERT_AUTOMATION} grava {@code null}
     * (não existe pessoa por trás de uma abertura automática). O caminho
     * MANUAL continua exigindo ator — a exigência vive no
     * {@code IncidentService} (service), não mais na coluna.
     */
    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Incident() {
        // JPA
    }

    /**
     * Incidente de domínio <b>manual</b> (Prompt 14): nasce
     * {@link IncidentStatus#OPEN} com {@link IncidentSource#MANUAL},
     * {@code resolvedAt} {@code null} e EXIGE quem abriu — o audit de
     * primeira linha não é opcional no caminho humano (o {@code null} da
     * coluna, desde a V12, é exclusivo do caminho automatizado).
     *
     * @param service        serviço dono (cadeia já provada no tenant)
     * @param title          título já sanitizado e validado pelo service
     * @param description    descrição sanitizada ou {@code null}
     * @param severity       severidade escolhida no create
     * @param startedAt      quando começou (payload ou relógio do domínio)
     * @param createdByUserId usuário autenticado que abriu o incidente
     */
    public Incident(
            Service service,
            String title,
            String description,
            AlertSeverity severity,
            Instant startedAt,
            UUID createdByUserId) {
        this(
                service,
                title,
                description,
                severity,
                startedAt,
                IncidentSource.MANUAL,
                Objects.requireNonNull(createdByUserId, "createdByUserId"));
    }

    /**
     * Incidente de domínio com ORIGEM explícita (Prompt 15) — o caminho
     * automatizado passa {@link IncidentSource#ALERT_AUTOMATION} com
     * {@code createdByUserId} {@code null}; o manual continua obrigado a
     * ter ator (checado aqui também, para a regra valer em qualquer
     * construtor).
     *
     * @param service        serviço dono (cadeia já provada no tenant)
     * @param title          título já sanitizado e validado pelo service
     * @param description    descrição sanitizada ou {@code null}
     * @param severity       severidade do alerta que originou ou escolhida
     * @param startedAt      quando começou (o alerta diz "quando começou")
     * @param source         origem ({@code MANUAL} | {@code ALERT_AUTOMATION})
     * @param createdByUserId quem abriu — {@code null} SOMENTE quando a
     *                        origem não é humana
     */
    public Incident(
            Service service,
            String title,
            String description,
            AlertSeverity severity,
            Instant startedAt,
            IncidentSource source,
            UUID createdByUserId) {
        // valida ANTES de mutar: entrada inválida não deixa o objeto meio-criado
        Objects.requireNonNull(source, "source");
        if (source == IncidentSource.MANUAL) {
            Objects.requireNonNull(createdByUserId, "createdByUserId");
        }
        this.service = Objects.requireNonNull(service, "service");
        this.title = Objects.requireNonNull(title, "title");
        this.description = description;
        this.severity = Objects.requireNonNull(severity, "severity");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.source = source;
        this.createdByUserId = createdByUserId;
        this.status = IncidentStatus.OPEN;
        this.resolvedAt = null;
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

    // --- sanitização --------------------------------------------------------

    /**
     * Fonte ÚNICA do texto persistido: remove caracteres de controle ISO (que
     * o {@code VARCHAR} aceitaria mas a UI/JSON não), trima e trunca em
     * {@code max} com corte seguro de surrogate pair — mesmo algoritmo de
     * {@code Alert.sanitizeMessage}, parametrizado pelo limite da coluna.
     *
     * @param raw texto bruto ({@code null} vira string vazia)
     * @param max limite da coluna (200 no título, 4000 na descrição)
     * @return texto seguro para o {@code VARCHAR} + CHECK de não-vazio
     */
    public static String sanitizeText(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= ' ' && c != '\u007F' && (c < '\u0080' || c > '\u009F')) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.length() > max) {
            int cut = max;
            // Não corta no meio de um surrogate pair.
            if (Character.isHighSurrogate(cleaned.charAt(cut - 1))) {
                cut--;
            }
            cleaned = cleaned.substring(0, cut);
        }
        return cleaned;
    }

    // --- mutações (somente pelo IncidentService) ----------------------------

    /** Troca o título (já sanitizado/validado). Dispara TITLE_CHANGED no service. */
    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    /**
     * Troca a descrição. {@code ""} vira {@code null} (coluna anulável — não
     * existe "descrição vazia", existe "sem descrição").
     */
    public void setDescription(String description) {
        this.description = description == null || description.isEmpty() ? null : description;
    }

    /** Troca a severidade. Dispara SEVERITY_CHANGED no service. */
    public void setSeverity(AlertSeverity severity) {
        this.severity = Objects.requireNonNull(severity, "severity");
    }

    /**
     * ÚNICO caminho de transição de estado. Valida ANTES de mutar (entrada
     * inválida não pode deixar o incidente meio-mudado) e marca
     * {@code resolvedAt} exatamente quando o novo estado é
     * {@link IncidentStatus#RESOLVED}.
     *
     * @param target estado pedido (nulo, inválido ou igual ao atual →
     *               {@link IllegalStateException}; o service traduz em
     *               {@code 409 INCIDENT_STATUS_TRANSITION} ANTES de chamar)
     * @param when   instante da transição (relógio do domínio)
     */
    public void transitionTo(IncidentStatus target, Instant when) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(when, "when");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("invalid incident status transition: " + status + " -> " + target);
        }
        this.status = target;
        this.resolvedAt = target == IncidentStatus.RESOLVED ? when : null;
    }

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Service getService() {
        return service;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    /** Origem imutável: {@code MANUAL} ou {@code ALERT_AUTOMATION}. */
    public IncidentSource getSource() {
        return source;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
