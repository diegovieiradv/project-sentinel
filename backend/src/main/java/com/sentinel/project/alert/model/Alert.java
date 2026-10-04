package com.sentinel.project.alert.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.sentinel.project.incident.model.Incident;
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

/**
 * Alerta disparado (Prompt 13) — RESULTADO avaliado do Alert Engine, oposto
 * da {@link AlertRule} (que é só configuração). Sub-recurso de serviço na
 * hierarquia Organization → Project → Service → Alert, sempre ligado à regra
 * que o gerou.
 *
 * <p><b>Histórico imutável de configuração:</b> todo campo {@code rule_*} mais
 * {@code severity} são SNAPSHOT copiado da regra QUANDO o alerta nasce.
 * Editar a regra depois (PATCH) nunca reescreve o que já disparou, e a UI
 * mostra a condição que valia na época sem JOIN retroativo. Não existe
 * {@code rule_severity} separado de {@code severity} — a severidade do alerta
 * É o snapshot (correção da revisão: um único eixo).</p>
 *
 * <p><b>Mensagem é a do FIRING, sempre:</b> a resolução NUNCA sobrescreve a
 * mensagem — ela grava {@code resolvedAt} + {@code resolutionReason}
 * ({@link AlertResolutionReason}) e a evidência de <em>por que</em> o alerta
 * abriu permanece intacta em qualquer desfecho (correção da revisão).</p>
 *
 * <p><b>Máquina de estados fechada:</b> {@link AlertStatus#FIRING} →
 * {@link AlertStatus#RESOLVED}, apenas por {@link #resolve} (nunca o
 * contrário; reabrir é UM NOVO alerta). O índice parcial
 * {@code UNIQUE (alert_rule_id) WHERE status = 'FIRING'} da migration V10
 * garante no máximo um aberto por regra; a transição em si é amarrada pelo
 * CHECK {@code alerts_lifecycle_check}.</p>
 *
 * <p><b>Mensagem sanitizada na origem:</b> {@link #sanitizeMessage} remove
 * controles ISO, trima e trunca com segurança de surrogado em 500 — o CHECK
 * {@code alerts_message_not_blank} e o {@code VARCHAR(500)} da V10 nunca são
 * estourados pela aplicação.</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@link AlertRule}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V10 NÃO declara DEFAULT
 * no id), {@code createdAt}/{@code updatedAt} pelos callbacks JPA, construtor
 * protegido para o JPA + construtor público de domínio. Nunca é exposta cru
 * na API (contrato de saída: {@code AlertResponse}).</p>
 *
 * <p><b>{@code monitorId} é cópia do escopo da regra</b> ({@code null} em
 * Service-level): snapshot denormalizado de propósito, para a listagem não
 * precisar inicializar o proxy LAZY da regra por linha.</p>
 */
@Entity
@Table(name = "alerts")
public class Alert {

    /** Tamanho máximo de mensagem: {@code VARCHAR(500)} da migration V10. */
    public static final int MAX_MESSAGE_LENGTH = 500;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code AlertRule.service}):
     * a resposta expõe o contexto da hierarquia via serviço → projeto.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    /** Regra que gerou o alerta — {@code ON DELETE RESTRICT} (histórico). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_rule_id", nullable = false)
    private AlertRule alertRule;

    /**
     * SNAPSHOT do escopo da regra no disparo ({@code null} em Service-level).
     * Cópia do valor, não proxy: {@code monitor_id} é coluna própria na V10.
     */
    @Column(name = "monitor_id")
    private UUID monitorId;

    /**
     * Incidente a que este alerta está associado (Prompt 14) — coluna
     * {@code incident_id} da V11, {@code null} na grande maioria.
     *
     * <p><b>Associação manual, nunca automática:</b> só o IncidentService
     * escreve este vínculo (UPDATE condicional na própria regra, sem passar
     * por aqui) — o Alert Engine não conhece a coluna e nenhum alerta nasce
     * vinculado. LAZY de propósito: a listagem de alertas (AlertResponse) não
     * toca no incidente, então não há select extra por linha.</p>
     *
     * <p>{@code ON DELETE SET NULL} na migration: apagar um incidente (só
     * possível por SQL direto, a API não tem DELETE) preserva o alerta.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    /** {@code VARCHAR(10)} + CHECK {@code alerts_status_check}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertStatus status;

    /**
     * SNAPSHOT da severidade da regra no disparo — {@code VARCHAR(10)} + CHECK
     * {@code alerts_severity_check}. É o ÚNICO eixo de severidade do alerta.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertSeverity severity;

    /** Motivo do encerramento — {@code null} enquanto FIRING (CHECK da V10). */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_reason", length = 20)
    private AlertResolutionReason resolutionReason;

    /** Início do alerta — imutável após o disparo (reabrir é novo alerta). */
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Fim — {@code null} enquanto FIRING; preenchido por {@link #resolve}. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Última avaliação do engine sobre ESTE alerta (dedup via touch). */
    @Column(name = "last_evaluated_at", nullable = false)
    private Instant lastEvaluatedAt;

    /**
     * Valor que a condição via no disparo ({@code null} quando o tipo não
     * produz número, ex.: SERVICE_STATUS).
     */
    @Column(name = "trigger_value", precision = 19, scale = 4)
    private BigDecimal triggerValue;

    /** Mensagem do disparo — NUNCA alterada pela resolução (sanitizada). */
    @Column(nullable = false, length = 500)
    private String message;

    // --- snapshots da regra (VARCHAR + CHECKs da migration V10) ------------

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30)
    private AlertRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_operator", nullable = false, length = 30)
    private AlertOperator ruleOperator;

    @Column(name = "rule_threshold_numeric", precision = 19, scale = 4)
    private BigDecimal ruleThresholdNumeric;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_threshold_status", length = 20)
    private com.sentinel.project.model.ServiceStatus ruleThresholdStatus;

    /**
     * {@code window} é palavra reservada do PostgreSQL — backticks no mapeamento
     * (atalho do Hibernate), como em {@code AlertRule}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "`rule_window`", nullable = false, length = 30)
    private EvaluationWindow ruleWindow;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Alert() {
        // JPA
    }

    /**
     * Alerta de domínio: nasce {@link AlertStatus#FIRING}, copiando da regra
     * TODO o snapshot ({@code severity}, tipo, operador, thresholds, janela e
     * escopo de monitor) — fonte única, o construtor não aceita snapshot
     * solto para evitar divergência entre colunas.
     *
     * @param service      serviço dono (mesmo da regra)
     * @param alertRule    regra que disparou (ativa na hora)
     * @param message      mensagem do disparo (sanitizada aqui)
     * @param triggerValue valor observado ou {@code null}
     * @param now          instante do disparo (fonte única do relógio)
     */
    public Alert(Service service, AlertRule alertRule, String message, BigDecimal triggerValue, Instant now) {
        this.service = Objects.requireNonNull(service, "service");
        this.alertRule = Objects.requireNonNull(alertRule, "alertRule");
        this.message = sanitizeMessage(message);
        this.triggerValue = triggerValue;
        this.status = AlertStatus.FIRING;
        this.resolutionReason = null;
        this.startedAt = Objects.requireNonNull(now, "now");
        this.lastEvaluatedAt = Objects.requireNonNull(now, "now");
        this.resolvedAt = null;

        // Snapshot da regra no instante do disparo.
        this.severity = alertRule.getSeverity();
        this.ruleType = alertRule.getType();
        this.ruleOperator = alertRule.getOperator();
        this.ruleThresholdNumeric = alertRule.getThresholdNumeric();
        this.ruleThresholdStatus = alertRule.getThresholdStatus();
        this.ruleWindow = alertRule.getWindow();
        this.monitorId = alertRule.getMonitor() == null ? null : alertRule.getMonitor().getId();
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

    // --- sanitização -------------------------------------------------------

    /**
     * Fonte ÚNICA da mensagem persistida: remove caracteres de controle ISO
     * (que o {@code VARCHAR} aceitaria mas a UI/JSON não), trima e trunca em
     * {@value #MAX_MESSAGE_LENGTH} com corte seguro de surrogate pair (nunca
     * parte um emoji no meio, que produziria string inválida).
     *
     * @param raw mensagem bruta ({@code null} vira string vazia)
     * @return mensagem segura para {@code VARCHAR(500) NOT NULL} + CHECK
     *         {@code alerts_message_not_blank}
     */
    public static String sanitizeMessage(String raw) {
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
        if (cleaned.length() > MAX_MESSAGE_LENGTH) {
            int cut = MAX_MESSAGE_LENGTH;
            // Não corta no meio de um surrogate pair.
            if (Character.isHighSurrogate(cleaned.charAt(cut - 1))) {
                cut--;
            }
            cleaned = cleaned.substring(0, cut);
        }
        return cleaned;
    }

    // --- mutações (somente pelo AlertLifecycleService) ---------------------

    /**
     * Encerra o alerta — ÚNICO caminho para {@link AlertStatus#RESOLVED}.
     * Grava o motivo em coluna própria e NUNCA toca em {@code message},
     * {@code startedAt} nem nos snapshots (correção da revisão).
     *
     * @param reason motivo do encerramento (nunca {@code null})
     * @param when   instante da resolução
     */
    public void resolve(AlertResolutionReason reason, Instant when) {
        // valida ANTES de mutar: entrada inválida não pode deixar o alerta
        // meio-resolvido (RESOLVED sem motivo/instante).
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(when, "when");
        this.status = AlertStatus.RESOLVED;
        this.resolutionReason = reason;
        this.resolvedAt = when;
    }

    /**
     * Deduplicação: marca que o engine reavaliou o alerta ainda aberto sem
     * reabrir nem alterar o snapshot. Chamado a cada ciclo para a regra que já
     * tinha alerta FIRING.
     *
     * @param when         instante da avaliação
     * @param triggerValue valor observado agora (pode ser {@code null})
     */
    public void touch(Instant when, BigDecimal triggerValue) {
        this.lastEvaluatedAt = Objects.requireNonNull(when, "when");
        this.triggerValue = triggerValue;
    }

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Service getService() {
        return service;
    }

    public AlertRule getAlertRule() {
        return alertRule;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    /**
     * Incidente associado ou {@code null} — leitura pelo IncidentService (a
     * escrita do vínculo é o UPDATE condicional do
     * {@code AlertRepository#linkIncident}, nunca um setter).
     */
    public Incident getIncident() {
        return incident;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public AlertResolutionReason getResolutionReason() {
        return resolutionReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public BigDecimal getTriggerValue() {
        return triggerValue;
    }

    public String getMessage() {
        return message;
    }

    public AlertRuleType getRuleType() {
        return ruleType;
    }

    public AlertOperator getRuleOperator() {
        return ruleOperator;
    }

    public BigDecimal getRuleThresholdNumeric() {
        return ruleThresholdNumeric;
    }

    public com.sentinel.project.model.ServiceStatus getRuleThresholdStatus() {
        return ruleThresholdStatus;
    }

    public EvaluationWindow getRuleWindow() {
        return ruleWindow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
