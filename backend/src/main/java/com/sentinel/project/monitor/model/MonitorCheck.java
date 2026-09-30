package com.sentinel.project.monitor.model;

import java.time.Instant;
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
import jakarta.persistence.Table;

/**
 * Uma execução de checagem PERSISTIDA (Prompt 10) — linha imutável do
 * histórico, tabela {@code monitor_checks} (migration V8).
 *
 * <p><b>Log append-only:</b> a linha nasce completa no {@code @PrePersist}
 * e nunca muda (não existe {@code @PreUpdate} nem coluna
 * {@code updated_at}): o que aconteceu naquele instante é fato histórico.
 * Retenção/remoção futura é evolução posterior (Prompt 11+) — não existe
 * job de delete hoje.</p>
 *
 * <p><b>O que esta entidade É e o que NÃO é:</b> aqui vive o resultado de
 * UMA execução: instantes, duração, status HTTP (se houve resposta),
 * desfecho, categoria e uma mensagem de domínio curta. <b>Nunca</b> há
 * corpo da resposta, headers, cookies, tokens, URL com query string ou
 * stack trace — o transporte descarta o corpo (Prompt 09) e a mensagem é
 * sanitizada/truncada a 500 chars pelo {@code MonitorCheckRecorder}.</p>
 *
 * <p><b>Sem {@code service_id} redundante:</b> o serviço é derivável pelo
 * monitor ({@code monitor → service}), então a linha guarda apenas a FK
 * {@code monitor_id}. Guardar também o serviço duplicaria dado e abriria
 * duas fontes de verdade para a mesma hierarquia.</p>
 *
 * <p><b>Idempotência SEM unique de timestamp:</b> não existe
 * {@code UNIQUE (monitor_id, started_at)} — testes com {@code Clock}
 * congelado produziriam {@code startedAt} idêntico e a segunda execução
 * seria silenciosamente descartada (falso-negativo). A garantia de "exatamente
 * 1 check por execução normal" vem da arquitetura (guarda de in-flight +
 * task única por execução) e é provada por teste de contagem — ver
 * {@code docs/operational-state.md}.</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@code Monitor}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V8 portanto NÃO
 * declara {@code DEFAULT} no id), {@code createdAt} mantido pelo callback de
 * vida JPA, construtor protegido para o JPA + construtor público de
 * domínio. A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code MonitorCheckResponse}).</p>
 */
@Entity
@Table(name = "monitor_checks")
public class MonitorCheck {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>LAZY de propósito</b> (diferente de {@code Monitor.service}): o
     * escritor já tem o monitor na mão ({@code getReferenceById}, sem SELECT
     * extra) e o leitor só precisa de {@code monitor.id} — que o Hibernate
     * resolve pela coluna FK sem inicializar a associação.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    /** Início da execução (relógio do Sentinel) — ordena a janela de avaliação. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Fim da execução — sempre {@code >= startedAt}. */
    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    /** Duração calculada de {@code startedAt} a {@code finishedAt}, em ms. */
    @Column(name = "duration_millis", nullable = false)
    private long durationMillis;

    /** Status HTTP recebido; {@code null} quando não houve resposta (rede falhou). */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Desfecho persistido — espelha o CHECK da V8. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private CheckOutcome outcome;

    /** O porquê da falha; {@code null} em sucesso — espelha o CHECK da V8. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 30)
    private CheckFailureCategory failureCategory;

    /** Mensagem de domínio SANITIZADA e truncada (VARCHAR(500), nunca corpo/URL/secrets). */
    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MonitorCheck() {
        // JPA
    }

    /**
     * Check de domínio: valores já calculados pelo {@code MonitorCheckRecorder}
     * a partir do {@code MonitorExecutionResult} (desfecho/categoria
     * traduzidos, mensagem sanitizada, duração derivada dos instantes).
     *
     * @param monitor         monitor dono da execução (persistido)
     * @param startedAt       instante de início
     * @param finishedAt      instante de término
     * @param durationMillis  duração em milissegundos
     * @param httpStatus      status HTTP recebido ou {@code null}
     * @param outcome         desfecho persistido
     * @param failureCategory categoria da falha ou {@code null}
     * @param message         mensagem de domínio (≤ 500 chars)
     */
    public MonitorCheck(
            Monitor monitor,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            Integer httpStatus,
            CheckOutcome outcome,
            CheckFailureCategory failureCategory,
            String message) {
        this.monitor = monitor;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMillis = durationMillis;
        this.httpStatus = httpStatus;
        this.outcome = outcome;
        this.failureCategory = failureCategory;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public CheckOutcome getOutcome() {
        return outcome;
    }

    public CheckFailureCategory getFailureCategory() {
        return failureCategory;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
