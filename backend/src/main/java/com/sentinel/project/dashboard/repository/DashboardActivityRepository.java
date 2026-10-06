package com.sentinel.project.dashboard.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sentinel.project.incident.model.IncidentEvent;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Feed de atividade recente do painel (Prompt 16) — a ÚNICA leitura que
 * cruza domínios (alertas × incidentes × timeline) e por isso mora no
 * próprio read model em vez de um repositório de agregado.
 *
 * <p><b>Somente leitura, por contrato:</b> a interface estende
 * {@link Repository} (marcador) e NÃO {@code JpaRepository} — não existe
 * {@code save}/{@code delete} no vocabulário desta consulta; nenhum método
 * {@code @Modifying} é declarado. O painel não escreve nada em lugar nenhum.</p>
 *
 * <p><b>Uma única consulta:</b> a UNIÃO das três fontes já filtrada pela
 * janela do painel, ordenada e com {@code LIMIT} — cinco selects (um por
 * ramo {@code UNION ALL}) em Java seriam cinco round-trips e a ordenação
 * ficaria no cliente.</p>
 *
 * <p><b>Semântica da janela:</b> {@code [windowFrom, windowTo)} — o MESMO
 * recorte do {@code availability}, para que o feed e os números do painel
 * falem do mesmo período. Um alerta que começou antes da janela mas foi
 * resolvido dentro dela entra pelo evento {@code ALERT_RESOLVED} (o
 * timestamp de cada linha é o do PRÓPRIO evento).</p>
 */
public interface DashboardActivityRepository extends Repository<IncidentEvent, UUID> {

    /**
     * Atividade do projeto na janela, mais recente primeiro, com desempate
     * por tipo e id (mesma estabilidade das demais ordenações do projeto).
     *
     * <p>As quatro linhas de tempo de alerta/incidente usam o timestamp do
     * próprio acontecimento ({@code started_at}/{@code resolved_at}) e as
     * linhas da timeline usam {@code incident_events.created_at} — só os
     * tipos {@code STATUS_CHANGED} e {@code ALERT_LINKED} entram, porque os
     * demais da tabela não são "atividade operacional" do painel.</p>
     *
     * @param projectId  projeto já provado dentro do tenant
     * @param windowFrom início da janela (incluso)
     * @param windowTo   fim da janela (excluso)
     * @param limit      máximo de linhas (20 no contrato do painel)
     */
    @Query(
            value = """
                    SELECT * FROM (
                        SELECT a.started_at AS "timestamp",
                               'ALERT_FIRED' AS "type",
                               a.service_id AS "serviceId",
                               s.name AS "serviceName",
                               a.severity AS "severity",
                               a.message AS "title",
                               a.id AS "resourceId",
                               'ALERT' AS "resourceType"
                        FROM alerts a
                        JOIN services s ON s.id = a.service_id
                        WHERE s.project_id = :projectId
                          AND a.started_at >= :windowFrom
                          AND a.started_at < :windowTo

                        UNION ALL

                        SELECT a.resolved_at,
                               'ALERT_RESOLVED',
                               a.service_id,
                               s.name,
                               a.severity,
                               a.message,
                               a.id,
                               'ALERT'
                        FROM alerts a
                        JOIN services s ON s.id = a.service_id
                        WHERE s.project_id = :projectId
                          AND a.resolved_at IS NOT NULL
                          AND a.resolved_at >= :windowFrom
                          AND a.resolved_at < :windowTo

                        UNION ALL

                        SELECT i.started_at,
                               'INCIDENT_CREATED',
                               i.service_id,
                               s.name,
                               i.severity,
                               i.title,
                               i.id,
                               'INCIDENT'
                        FROM incidents i
                        JOIN services s ON s.id = i.service_id
                        WHERE s.project_id = :projectId
                          AND i.started_at >= :windowFrom
                          AND i.started_at < :windowTo

                        UNION ALL

                        SELECT i.resolved_at,
                               'INCIDENT_RESOLVED',
                               i.service_id,
                               s.name,
                               i.severity,
                               i.title,
                               i.id,
                               'INCIDENT'
                        FROM incidents i
                        JOIN services s ON s.id = i.service_id
                        WHERE s.project_id = :projectId
                          AND i.resolved_at IS NOT NULL
                          AND i.resolved_at >= :windowFrom
                          AND i.resolved_at < :windowTo

                        UNION ALL

                        SELECT e.created_at,
                               CASE e.type
                                   WHEN 'STATUS_CHANGED' THEN 'INCIDENT_STATUS_CHANGED'
                                   WHEN 'ALERT_LINKED' THEN 'INCIDENT_LINKED'
                               END,
                               i.service_id,
                               s.name,
                               i.severity,
                               e.message,
                               i.id,
                               'INCIDENT'
                        FROM incident_events e
                        JOIN incidents i ON i.id = e.incident_id
                        JOIN services s ON s.id = i.service_id
                        WHERE s.project_id = :projectId
                          AND e.type IN ('STATUS_CHANGED', 'ALERT_LINKED')
                          AND e.created_at >= :windowFrom
                          AND e.created_at < :windowTo
                    ) activity
                    ORDER BY "timestamp" DESC, "resourceType" ASC, "resourceId" ASC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<ActivityRowProjection> findRecentActivity(
            @Param("projectId") UUID projectId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo,
            @Param("limit") int limit);
}
