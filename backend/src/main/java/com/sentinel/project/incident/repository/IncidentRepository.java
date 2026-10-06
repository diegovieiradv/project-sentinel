package com.sentinel.project.incident.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.incident.model.IncidentStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de incidentes (Prompt 14) — quarto nível da hierarquia
 * Organization → Project → Service → Incident.
 *
 * <p><b>Anti-IDOR mesmo padrão do {@code AlertRepository}:</b> a cadeia do
 * tenant prova Organization → Project → Service e TODA leitura daqui é
 * escopada por {@code serviceId} — nunca existe {@code findById} avulso como
 * base de autorização. O detalhe ({@link #findByServiceIdAndId}) prova
 * Service → Incident na própria consulta.</p>
 *
 * <p><b>Ordem fixa da listagem:</b> {@code startedAt DESC, id DESC} — "o que
 * começou por último primeiro" com desempate determinístico no id, imposta
 * pelo service via {@link Pageable} (mesma convenção do
 * {@code AlertService}/{@code AlertRuleService}). O índice
 * {@code idx_incidents_service_started_desc} da V11 cobre o caso sem filtro.
 * {@code startedAt} é o eixo da listagem justamente porque é imutável (ao
 * contrário de {@code updatedAt}, que mudaria com cada edição).</p>
 *
 * <p><b>Filtros opcionais status/severity</b> ({@code (:p IS NULL OR ...)}):
 * um único {@code @Query} em vez de 4 derived queries — parâmetro nulo não
 * restringe a linha, e enum inválido é rejeitado com 400 ANTES de chegar
 * aqui (bind de enum JPA falharia).</p>
 */
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /** Lookup do detalhe: prova Service → Incident. Nunca findById avulso. */
    Optional<Incident> findByServiceIdAndId(UUID serviceId, UUID incidentId);

    /**
     * Correlação da automação Alert → Incident (Prompt 15): o incidente
     * AUTOMÁTICO ainda aberto do serviço — a linha que uma segunda regra do
     * mesmo serviço deve REUTILIZAR em vez de criar outra. Escopado por
     * {@code serviceId} (nunca cross-service), filtrado por
     * {@code source = ALERT_AUTOMATION} (incidentes MANUAIS ficam de fora:
     * a automação não "herda" um incidente que um humano abriu) e por
     * {@code status <> RESOLVED} (encerrado não é reaberto — o próximo
     * disparo nasce um novo). O índice parcial
     * {@code uq_incidents_auto_open_per_service} da V12 garante no máximo UMA
     * linha por serviço; este método devolve a primeira ({@code LIMIT 1} é
     * implícito no {@code Optional}).
     *
     * @param serviceId serviço dono (proveniente do alerta elegível)
     */
    Optional<Incident> findByServiceIdAndSourceAndStatusNot(
            UUID serviceId, IncidentSource source, IncidentStatus status);

    /**
     * Listagem paginada por serviço com os dois filtros opcionais da API.
     *
     * @param serviceId serviço dono (já provado na cadeia do tenant)
     * @param status    filtro opcional por status (OPEN/INVESTIGATING/…)
     * @param severity  filtro opcional por severidade (SEV1–SEV4)
     */
    @Query("""
            SELECT i
            FROM Incident i
            WHERE i.service.id = :serviceId
              AND (:status IS NULL OR i.status = :status)
              AND (:severity IS NULL OR i.severity = :severity)
            """)
    Page<Incident> search(
            @Param("serviceId") UUID serviceId,
            @Param("status") IncidentStatus status,
            @Param("severity") AlertSeverity severity,
            Pageable pageable);

    // --- painel do projeto (Prompt 16) -------------------------------------

    /**
     * Incidentes ATIVOS do projeto por status (GROUP BY) —
     * {@code status <> RESOLVED} está na query, então só os três estados
     * ativos podem aparecer e a soma fecha com {@code activeTotal} do
     * painel.
     *
     * @param projectId projeto já provado dentro do tenant
     */
    @Query(
            value = """
                    SELECT i.status AS "status", count(*) AS "total"
                    FROM incidents i
                    JOIN services s ON s.id = i.service_id
                    WHERE s.project_id = :projectId
                      AND i.status <> 'RESOLVED'
                    GROUP BY i.status
                    """,
            nativeQuery = true)
    List<IncidentStatusCountProjection> countActiveByStatusForProject(
            @Param("projectId") UUID projectId);

    /**
     * Primeiros incidentes ativos do projeto para a lista do painel —
     * severidade SEV1 → SEV4, depois o que começou por último, com desempate
     * no id.
     *
     * <p>{@code associatedAlertsCount} é uma subquery correlacionada na
     * PRÓPRIA linha (no máximo {@code limit} linhas): uma consulta por
     * incidente seria N+1 na API.</p>
     *
     * @param projectId projeto já provado dentro do tenant
     * @param limit     máximo de linhas (10 no contrato do painel)
     */
    @Query(
            value = """
                    SELECT i.id AS "incidentId",
                           i.service_id AS "serviceId",
                           s.name AS "serviceName",
                           i.title AS "title",
                           i.severity AS "severity",
                           i.status AS "status",
                           i.source AS "source",
                           i.started_at AS "startedAt",
                           (
                               SELECT count(*)
                               FROM alerts a
                               WHERE a.incident_id = i.id
                           ) AS "associatedAlertsCount"
                    FROM incidents i
                    JOIN services s ON s.id = i.service_id
                    WHERE s.project_id = :projectId
                      AND i.status <> 'RESOLVED'
                    ORDER BY CASE i.severity WHEN 'SEV1' THEN 1
                                 WHEN 'SEV2' THEN 2
                                 WHEN 'SEV3' THEN 3 ELSE 4 END,
                             i.started_at DESC, i.id DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<ActiveIncidentRowProjection> findTopActiveForProject(
            @Param("projectId") UUID projectId, @Param("limit") int limit);
}
