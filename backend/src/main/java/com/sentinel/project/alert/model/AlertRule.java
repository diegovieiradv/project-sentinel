package com.sentinel.project.alert.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.Monitor;

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
 * Regra de alerta (Prompt 12) — sub-recurso de serviço na hierarquia
 * Organization → Project → Service → AlertRule, com escopo opcional de um
 * Monitor do MESMO serviço.
 *
 * <p><b>O que esta entidade É e o que NÃO é:</b> aqui vive apenas a
 * <b>configuração declarativa</b> de uma condição (tipo, operador, threshold,
 * janela, severidade). NÃO existe coluna de resultado — nenhum valor avaliado,
 * nenhum alerta disparado, nenhum incidente: a AVALIAÇÃO automática é do Alert
 * Engine (Prompt 13) e a ausência total desse material é contrato deste
 * prompt, não omissão.</p>
 *
 * <p><b>Threshold numérico × status (type-safe, nunca string em número):</b>
 * dois campos mutuamente exclusivos — {@code thresholdNumeric} para os quatro
 * tipos numéricos e {@code thresholdStatus} APENAS para
 * {@link AlertRuleType#SERVICE_STATUS}. A exclusividade é validada na
 * aplicação ({@code AlertRuleValidationMatrix}) e amarrada pelos CHECKs
 * condicionais da migration V9.</p>
 *
 * <p><b>Unicidade por serviço:</b> {@code name_normalized} (trim + lowercase,
 * {@link Locale#ROOT}) é persistido e tem
 * {@code UNIQUE (service_id, name_normalized)} na migration V9 — o mesmo nome
 * normalizado não existe duas vezes no mesmo serviço, ativo OU inativo.
 * Serviços diferentes podem repetir nome. Pré-check 409 no service + corrida
 * absorvida pelo constraint (também 409, nunca SQL vazando).</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@code Monitor}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V9 portanto NÃO declara
 * {@code DEFAULT} no id), {@code createdAt}/{@code updatedAt} mantidos pelos
 * callbacks de vida JPA, construtor protegido para o JPA + construtor público
 * de domínio. A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code AlertRuleResponse}).</p>
 *
 * <p><b>{@code active} é propriedade de servidor:</b> nasce {@code true} no
 * construtor de domínio e só muda pela rota de ativação
 * ({@code setActive} é chamado exclusivamente pelo {@code AlertRuleService}) —
 * create/update não o controlam por payload.</p>
 *
 * <p><b>Fetch do monitor:</b> {@code LAZY} de propósito — a resposta expõe
 * apenas o {@code monitorId} (lido dentro da transação de leitura, e getId() de
 * proxy não inicializa), então a listagem paginada não paga um select por
 * linha. Já {@code service} é {@code EAGER} pelo mesmo racional do
 * {@code Monitor.service}: a resposta percorre serviço → projeto →
 * organização.</p>
 */
@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code Monitor.service}):
     * a resposta expõe {@code organizationId}/{@code projectId}/{@code serviceId}
     * via serviço → projeto, e o lookup fora de transação explodiria em
     * {@code LazyInitializationException} com um proxy LAZY.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    /**
     * Escopo opcional: quando presente a regra avalia a métrica DESTE
     * monitor; quando {@code null} é Service-level (métrica agregada do
     * serviço). O monitor deve pertencer ao MESMO serviço (ownership
     * revalidado no create/update). {@code ON DELETE RESTRICT} na V9: a linha
     * é histórico da configuração.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_id")
    private Monitor monitor;

    /** Teto de 120 = @Size(max=120) da API e VARCHAR(120) da migration V9. */
    @Column(nullable = false, length = 120)
    private String name;

    /** Chave de unicidade por serviço: {@link #normalizeName(String)}. */
    @Column(name = "name_normalized", nullable = false, length = 120)
    private String nameNormalized;

    /**
     * Tipo da condição — {@code length = 30} espelha {@code VARCHAR(30)} +
     * CHECK {@code alert_rules_type_check} da migration V9.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private AlertRuleType type;

    /** Operador da comparação — matriz type × operator validada na aplicação. */
    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 30)
    private AlertOperator operator;

    /**
     * Threshold DOS quatro tipos numéricos (percentual, ms ou contagem) —
     * {@code null} obrigatoriamente quando {@code type = SERVICE_STATUS}
     * (CHECK {@code alert_rules_threshold_numeric_check} da V9). Precisão
     * {@code NUMERIC(19,4)}: percentuais limitados a 2 casas pela aplicação,
     * latência aceita fração, contagem exige escala 0.
     */
    @Column(name = "threshold_numeric", precision = 19, scale = 4)
    private BigDecimal thresholdNumeric;

    /**
     * Threshold APENAS de {@link AlertRuleType#SERVICE_STATUS}
     * ({@code DEGRADED}/{@code UNAVAILABLE} — nunca número dentro de campo
     * decimal). {@code null} nos demais tipos (CHECK
     * {@code alert_rules_threshold_status_check} da V9).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_status", length = 20)
    private ServiceStatus thresholdStatus;

    /**
     * Janela de avaliação — {@code VARCHAR(30)} + CHECK da V9.
     *
     * <p><b>Identificador citado:</b> {@code window} é palavra reservada do
     * PostgreSQL (funções de janela), então o nome da coluna vai entre
     * backticks (atalho do Hibernate para aspas duplas: o DDL da V9 usa
     * {@code "window"}). O nome lógico da coluna continua {@code window} —
     * contrato JSON e JPQL não mudam.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "`window`", nullable = false, length = 30)
    private EvaluationWindow window;

    /** Severidade SEV1–SEV4 — {@code VARCHAR(10)} + CHECK da V9. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private AlertSeverity severity;

    /** Nasce {@code true}; é desativada pela rota de ativação (soft-delete). */
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AlertRule() {
        // JPA
    }

    /**
     * Regra de domínio: nasce ativa, com a normalização do nome calculada no
     * próprio construtor (fonte única da regra trim + lowercase).
     *
     * <p>Os valores chegam JÁ validados pelo {@code AlertRuleService} via
     * {@code AlertRuleValidationMatrix} (matriz type × operator, faixa do
     * threshold, exclusividade numérico × status, janela e severidade) — o
     * construtor não duplica essa regra.</p>
     *
     * @param service          serviço dono (já resolvido na cadeia do tenant)
     * @param name             nome já validado e trimado pela aplicação
     * @param monitor          escopo de monitor ou {@code null} para Service-level
     * @param type             tipo da condição
     * @param operator         operador compatível com o tipo
     * @param thresholdNumeric threshold numérico ou {@code null} para SERVICE_STATUS
     * @param thresholdStatus  status do threshold ou {@code null} nos tipos numéricos
     * @param window           janela de avaliação
     * @param severity         severidade SEV1–SEV4
     */
    public AlertRule(
            Service service,
            String name,
            Monitor monitor,
            AlertRuleType type,
            AlertOperator operator,
            BigDecimal thresholdNumeric,
            ServiceStatus thresholdStatus,
            EvaluationWindow window,
            AlertSeverity severity) {
        this.service = service;
        this.name = name;
        this.nameNormalized = normalizeName(name);
        this.monitor = monitor;
        this.type = type;
        this.operator = operator;
        this.thresholdNumeric = thresholdNumeric;
        this.thresholdStatus = thresholdStatus;
        this.window = window;
        this.severity = severity;
        this.active = true;
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

    // --- mutações (somente pelo AlertRuleService) --------------------------

    /** Renomeia e recalcula a chave de unicidade — único caminho pós-criação. */
    public void rename(String name) {
        this.name = name;
        this.nameNormalized = normalizeName(name);
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public void setType(AlertRuleType type) {
        this.type = type;
    }

    public void setOperator(AlertOperator operator) {
        this.operator = operator;
    }

    public void setThresholdNumeric(BigDecimal thresholdNumeric) {
        this.thresholdNumeric = thresholdNumeric;
    }

    public void setThresholdStatus(ServiceStatus thresholdStatus) {
        this.thresholdStatus = thresholdStatus;
    }

    public void setWindow(EvaluationWindow window) {
        this.window = window;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    /** Ativação/desativação idempotente — só o service decide se há escrita. */
    public void setActive(boolean active) {
        this.active = active;
    }

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Service getService() {
        return service;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public AlertRuleType getType() {
        return type;
    }

    public AlertOperator getOperator() {
        return operator;
    }

    public BigDecimal getThresholdNumeric() {
        return thresholdNumeric;
    }

    public ServiceStatus getThresholdStatus() {
        return thresholdStatus;
    }

    public EvaluationWindow getWindow() {
        return window;
    }

    public AlertSeverity getSeverity() {
        return severity;
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
}
