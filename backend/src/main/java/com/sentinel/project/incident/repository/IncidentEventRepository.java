package com.sentinel.project.incident.repository;

import java.util.List;
import java.util.UUID;

import com.sentinel.project.incident.model.IncidentEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Timeline append-only de UM incidente (Prompt 14) — tabela
 * {@code incident_events} (migration V11).
 *
 * <p><b>Única leitura do log:</b> o GET
 * {@code .../incidents/{incidentId}/events} devolve a timeline INTEIRA na
 * ordem {@code createdAt ASC, id ASC} — exatamente a ordenação coberta pelo
 * índice {@code idx_incident_events_incident_created}. A ordem é a da EMISSÃO
 * dos eventos (quem mudou primeiro aparece primeiro), com desempate no id
 * porque o {@code createdAt} de eventos do mesmo request pode cair no mesmo
 * microssegundo (o {@code IncidentService} garante instantes estritamente
 * crescentes para evitar depender do desempate).</p>
 *
 * <p>Não existe escrita por aqui: criar linha é papel exclusivo do
 * {@code IncidentService}, na MESMA transação da mudança que a originou.
 * Nenhum endpoint da API expõe PATCH/DELETE de evento.</p>
 */
public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {

    /** Timeline de um incidente na ordem de emissão (índice da V11). */
    @Query("""
            SELECT e
            FROM IncidentEvent e
            WHERE e.incidentId = :incidentId
            ORDER BY e.createdAt ASC, e.id ASC
            """)
    List<IncidentEvent> findByIncidentId(@Param("incidentId") UUID incidentId);
}
