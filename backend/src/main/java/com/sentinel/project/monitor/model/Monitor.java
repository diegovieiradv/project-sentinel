package com.sentinel.project.monitor.model;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

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
 * Monitor de um serviço (Prompt 08) — quarto nível da hierarquia
 * Organization → Project → Service → Monitor.
 *
 * <p><b>O que esta entidade É e o que NÃO é:</b> aqui vive apenas a
 * <b>configuração declarativa</b> de uma checagem de fronteira (alvo, método,
 * intervalo, timeout, participação no catálogo). NÃO existe coluna de
 * resultado — latência, status HTTP, falha, série temporal, janela de
 * observação ou status de saúde não são persistidos por este prompt; coleta e
 * execução entram em um prompt futuro com tabela própria.</p>
 *
 * <p><b>Unicidade por serviço:</b> {@code name_normalized} (trim + lowercase,
 * {@link Locale#ROOT}) é persistido e tem
 * {@code UNIQUE (service_id, name_normalized)} na migration V7 — o mesmo nome
 * normalizado não existe duas vezes no mesmo serviço, ativo OU inativo.
 * Serviços (e portanto projetos/organizações) diferentes podem repetir nome. A
 * aplicação faz um pré-check rápido (409 imediato) e o banco é a última linha
 * de defesa sob corrida (também 409, nunca SQL vazando).</p>
 *
 * <p><b>Padrão de entidade alinhado ao de {@code Service}:</b> UUID gerado
 * pela aplicação em {@code @PrePersist} (a migration V7 portanto NÃO declara
 * {@code DEFAULT} no id), {@code createdAt}/{@code updatedAt} mantidos pelos
 * callbacks de vida JPA, construtor protegido para o JPA + construtor público
 * de domínio. A entidade JPA nunca é exposta cru na API (contrato de saída:
 * {@code MonitorResponse}).</p>
 *
 * <p><b>{@code type} e {@code active} são propriedades de servidor:</b>
 * {@code type} nasce {@link MonitorType#HTTP} no construtor de domínio e não
 * tem setter; {@code active} nasce {@code true} e só muda pela rota de
 * ativação ({@code setActive} é chamado exclusivamente pelo
 * {@code MonitorService}) — create/update não o controlam por payload.</p>
 *
 * <p><b>URL validada ANTES de persistir:</b> o {@code SsrfGuard} rejeita
 * alvos internos/privados no create, em todo update que troca a URL e na
 * reativação (false→true). A coluna guarda a URL canônica escolhida pelo
 * usuário; a revalidação por conexão real é responsabilidade do prompt 09
 * (ver javadoc do guard).</p>
 */
@Entity
@Table(name = "monitors")
public class Monitor {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * <b>EAGER de propósito</b> (mesmo racional do {@code Service.project}):
     * a resposta expõe {@code organizationId}/{@code projectId} via serviço →
     * projeto, e o lookup fora de transação explodiria em
     * {@code LazyInitializationException} com um proxy LAZY.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    /** Teto de 120 = @Size(max=120) da API e VARCHAR(120) da migration V7. */
    @Column(nullable = false, length = 120)
    private String name;

    /** Chave de unicidade por serviço: {@link #normalizeName(String)}. */
    @Column(name = "name_normalized", nullable = false, length = 120)
    private String nameNormalized;

    /**
     * Invariante do cadastro: nasce {@link MonitorType#HTTP} e não tem setter
     * (nenhuma API o altera). {@code length = 10} espelha {@code VARCHAR(10)}
     * + CHECK da migration V7.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private MonitorType type;

    /** Alvo da checagem — já passou pelo {@code SsrfGuard} antes de persistir. */
    @Column(nullable = false, length = 2048)
    private String url;

    /** {@code GET} ou {@code HEAD} — validated pela aplicação + CHECK V7. */
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 10)
    private MonitorMethod method;

    /** Distância entre checagens, em segundos (30..86400, default 60). */
    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    /**
     * Limite de tempo da checagem, em milissegundos (100..30000, default 5000)
     * — SEMPRE menor que {@code intervalSeconds * 1000} (coerência da V7).
     */
    @Column(name = "timeout_millis", nullable = false)
    private int timeoutMillis;

    /** Nasce {@code true}; é desativado pela rota de ativação (soft-delete). */
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Monitor() {
        // JPA
    }

    /**
     * Monitor de domínio: nasce ativo, com tipo {@code HTTP} (invariante) e a
     * normalização do nome calculada no próprio construtor (fonte única da
     * regra trim + lowercase).
     *
     * <p>Os valores de método/intervalo/timeout chegam JÁ validados pelo
     * {@code MonitorService} (faixas e coerência) — o construtor não duplica
     * essa regra.</p>
     *
     * @param service         serviço dono (já resolvido e validado como ativo)
     * @param name            nome já validado e trimado pela aplicação
     * @param url             alvo já validado pelo {@code SsrfGuard}
     * @param method          {@code GET} ou {@code HEAD}
     * @param intervalSeconds 30..86400
     * @param timeoutMillis   100..30000 e &lt; intervalSeconds * 1000
     */
    public Monitor(
            Service service,
            String name,
            String url,
            MonitorMethod method,
            int intervalSeconds,
            int timeoutMillis) {
        this.service = service;
        this.name = name;
        this.nameNormalized = normalizeName(name);
        this.type = MonitorType.HTTP;
        this.url = url;
        this.method = method;
        this.intervalSeconds = intervalSeconds;
        this.timeoutMillis = timeoutMillis;
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

    // --- mutações (somente pelo MonitorService) ----------------------------

    /** Renomeia e recalcula a chave de unicidade — único caminho pós-criação. */
    public void rename(String name) {
        this.name = name;
        this.nameNormalized = normalizeName(name);
    }

    /** Troca o alvo — chamado só depois do {@code SsrfGuard} aceitar a URL. */
    public void setUrl(String url) {
        this.url = url;
    }

    public void setMethod(MonitorMethod method) {
        this.method = method;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** Ativação/desativação idempotente — só o service decide se há escrita. */
    public void setActive(boolean active) {
        this.active = active;
    }

    // Não existe setType: o tipo é invariante do cadastro neste prompt.

    // --- leitura ------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Service getService() {
        return service;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public MonitorType getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public MonitorMethod getMethod() {
        return method;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
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
