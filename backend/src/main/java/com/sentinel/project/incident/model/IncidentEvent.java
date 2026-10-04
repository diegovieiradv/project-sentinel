package com.sentinel.project.incident.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Linha da timeline de um incidente (Prompt 14) — log APPEND-ONLY do ciclo
 * de vida, na tabela {@code incident_events} (migration V11).
 *
 * <p><b>Append-only de verdade:</b> a entidade não tem {@code updatedAt}, a
 * V11 não cria coluna de atualização, o JPA só recebe
 * {@code @PrePersist} e a API só expõe GET
 * ({@code .../incidents/{incidentId}/events}) — não existe PATCH nem DELETE
 * de evento em nenhum caminho. Uma vez escrita, a linha é fato histórico.</p>
 *
 * <p><b>Mensagem determinística</b> (contrato público, literal — critérios
 * 23–34):</p>
 * <ul>
 *   <li>{@code "Incident created"}</li>
 *   <li>{@code "Status changed from X to Y"}</li>
 *   <li>{@code "Severity changed from A to B"}</li>
 *   <li>{@code "Title changed"} / {@code "Description changed"}</li>
 *   <li>{@code "Alert <uuid> linked"} / {@code "Alert <uuid> unlinked"}</li>
 * </ul>
 * <p>sem timestamp, autor ou detalhe embutido na frase — os metadados moram
 * em colunas próprias.</p>
 *
 * <p><b>{@code incidentId} é coluna simples</b> (UUID, FK na V11) e não
 * associação JPA: a timeline é lida por {@code incident_id} e nunca precisa
 * do aggregate do incidente — sem proxy LAZY, sem fetch extra.</p>
 *
 * <p><b>{@code actorUserId} nullable</b> e apagado em cascata suave
 * ({@code ON DELETE SET NULL}): o evento permanece mesmo que a conta de quem
 * o causou saia do sistema. O caminho manual preenche sempre (toda mutação
 * exige usuário autenticado); desde o Prompt 15 a automação Alert →
 * Incident grava {@code null} de propósito — os eventos
 * {@code CREATED}/{@code ALERT_LINKED} de um incidente automático não têm
 * pessoa por trás (o campo não era NOT NULL "de propósito" e a V12 tirou o
 * NOT NULL do {@code incidents.created_by_user_id} pelo mesmo motivo).</p>
 */
@Entity
@Table(name = "incident_events")
public class IncidentEvent {

    /** Mensagem máxima: {@code VARCHAR(1000)} da migration V11. */
    public static final int MAX_MESSAGE_LENGTH = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    /** {@code VARCHAR(30)} + CHECK {@code incident_events_type_check}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IncidentEventType type;

    @Column(nullable = false, length = MAX_MESSAGE_LENGTH)
    private String message;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IncidentEvent() {
        // JPA
    }

    /**
     * Evento de domínio — o service monta a mensagem determinística.
     * O instante é o do {@code @PrePersist} ({@link Instant#now()}).
     *
     * @param incidentId incidente dono (coluna própria, FK da V11)
     * @param type       tipo do vocabulário fechado
     * @param message    frase pronta (≤ {@value #MAX_MESSAGE_LENGTH}, não vazia)
     * @param actorUserId usuário que causou o evento ou {@code null}
     */
    public IncidentEvent(
            UUID incidentId,
            IncidentEventType type,
            String message,
            UUID actorUserId) {
        this(incidentId, type, message, actorUserId, null);
    }

    /**
     * Evento de domínio com o instante FORNECIDO — usado quando UMA operação
     * emite VÁRIOS eventos (um PATCH que muda title + description + severity)
     * e a ordem da timeline precisa ser a da emissão.
     *
     * <p><b>Por que existe:</b> a ordenação do GET {@code .../events} é
     * {@code createdAt ASC, id ASC}, e {@code id} é um UUID aleatório — se
     * dois eventos do mesmo request caíssem no MESMO microssegundo (resolução
     * do {@code TIMESTAMPTZ}), o desempate sortearia a ordem e o contrato
     * "title → description → severity" viraria sorte. O
     * {@code IncidentService} passa instantes estritamente crescentes com
     * folha de 1&nbsp;ms para que o desempate por id nunca seja acionado.</p>
     *
     * <p>{@code null} devolve o comportamento do construtor de 4 argumentos
     * ({@code @PrePersist} preenche com {@link Instant#now()}).</p>
     *
     * @param incidentId incidente dono (coluna própria, FK da V11)
     * @param type       tipo do vocabulário fechado
     * @param message    frase pronta (≤ {@value #MAX_MESSAGE_LENGTH}, não vazia)
     * @param actorUserId usuário que causou o evento ou {@code null}
     * @param createdAt  instante da linha ou {@code null} para o relógio
     */
    public IncidentEvent(
            UUID incidentId,
            IncidentEventType type,
            String message,
            UUID actorUserId,
            Instant createdAt) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.type = Objects.requireNonNull(type, "type");
        this.message = Objects.requireNonNull(message, "message");
        this.actorUserId = actorUserId;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        // createdAt com o relógio REAL (não o injetado): em testes com relógio
        // congelado, eventos do MESMO incidente compartilhariam o mesmo
        // instante e a ordenação da timeline cairia no desempate por id.
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public IncidentEventType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
