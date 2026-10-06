package com.sentinel.project.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de serviços (Prompt 07).
 *
 * <p><b>Por que a cadeia é provada em DUAS consultas:</b> a autorização
 * Organization → Project → Service não pode ser uma query derived
 * {@code findByOrganizationId...} em {@code Service} — a entidade não tem a
 * propriedade {@code organizationId} (o caminho seria
 * {@code project.organization.id}) e o Spring Data não inventa esse predicado.
 * O lookup canônico portanto é composto:</p>
 * <ol>
 *   <li>{@code ProjectRepository.findByOrganizationIdAndId(orgId, projectId)}
 *       prova <b>Organization → Project</b> (e é o que permite checar se o
 *       projeto está ativo);</li>
 *   <li>{@link #findByProjectIdAndId(projectId, serviceId)} prova
 *       <b>Project → Service</b>.</li>
 * </ol>
 * <p>Como o projeto já foi resolvido dentro do tenant, todo o resto do
 * repositório pode ser escopado apenas por {@code projectId} — não existe
 * caminho de leitura global de serviços e {@code findById} avulso nunca é
 * usado como base de autorização.</p>
 *
 * <p>As variantes de listagem variam nos filtros opcionais (active, status e
 * busca por nome) e recebem o {@link Pageable} já montado pelo service com a
 * ordenação determinística <b>createdAt asc, id asc</b>.</p>
 *
 * <p>{@code ...NameNormalizedContaining} casa contra a coluna normalizada
 * (minúsculas), então a busca por nome é case-insensitive por construção,
 * sem {@code LOWER(coluna)} na query.</p>
 */
public interface ServiceRepository extends JpaRepository<Service, UUID> {

    /** Lookup do detalhe: prova Project → Service. Nunca findById avulso. */
    Optional<Service> findByProjectIdAndId(UUID projectId, UUID serviceId);

    /**
     * Lock pessimista (SELECT ... FOR UPDATE) da linha do serviço — usado
     * pelo {@code OperationalStateEvaluator} (Prompt 10) para serializar o
     * recálculo de {@code status} entre checagens concorrentes do MESMO
     * serviço: quem segura o lock lê as janelas de checks e decide a
     * escrita, e o próximo avaliador espera e parte do resultado já
     * gravado (nunca sobrescreve com estado calculado de janela velha).
     *
     * <p>Leitura+lock+escrita acontecem na MESMA transação do avaliador
     * ({@code REQUIRES_NEW}) — fora disso o lock não protegeria nada.</p>
     *
     * @param id serviço a travar
     * @return a linha travada, ou vazia se o serviço não existe
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Service s where s.id = :id")
    Optional<Service> findByIdWithLock(@Param("id") UUID id);

    /** Pré-check de unicidade (create) — 409 imediato, antes do banco. */
    boolean existsByProjectIdAndNameNormalized(UUID projectId, String nameNormalized);

    /** Pré-check de unicidade do rename (update) — exclui o próprio serviço. */
    boolean existsByProjectIdAndNameNormalizedAndIdNot(
            UUID projectId, String nameNormalized, UUID serviceId);

    // --- listagem: active × status × name (8 combinações de filtro) --------

    Page<Service> findByProjectId(UUID projectId, Pageable pageable);

    Page<Service> findByProjectIdAndActive(UUID projectId, boolean active, Pageable pageable);

    Page<Service> findByProjectIdAndStatus(UUID projectId, ServiceStatus status, Pageable pageable);

    Page<Service> findByProjectIdAndActiveAndStatus(
            UUID projectId, boolean active, ServiceStatus status, Pageable pageable);

    Page<Service> findByProjectIdAndNameNormalizedContaining(
            UUID projectId, String nameNormalized, Pageable pageable);

    Page<Service> findByProjectIdAndActiveAndNameNormalizedContaining(
            UUID projectId, boolean active, String nameNormalized, Pageable pageable);

    Page<Service> findByProjectIdAndStatusAndNameNormalizedContaining(
            UUID projectId, ServiceStatus status, String nameNormalized, Pageable pageable);

    Page<Service> findByProjectIdAndActiveAndStatusAndNameNormalizedContaining(
            UUID projectId,
            boolean active,
            ServiceStatus status,
            String nameNormalized,
            Pageable pageable);

    // --- painel do projeto (Prompt 16) -------------------------------------

    /**
     * Saúde do projeto: serviços por status em UMA consulta (GROUP BY) — o
     * painel nunca faz um select por serviço (N+1) e a soma das quatro
     * contagens fecha com o total de serviços.
     *
     * <p>Conta TODOS os serviços do projeto (ativos e inativos): o painel é
     * o retrato do catálogo, mesma leitura da métrica por serviço.</p>
     *
     * @param projectId projeto já provado dentro do tenant
     */
    @Query(
            value = """
                    SELECT s.status AS "status", count(*) AS "total"
                    FROM services s
                    WHERE s.project_id = :projectId
                    GROUP BY s.status
                    """,
            nativeQuery = true)
    List<ServiceStatusCountProjection> countStatusByProject(
            @Param("projectId") UUID projectId);

    /**
     * Serviços que pedem atenção agora, resolvidos INTEIROS no banco
     * (filtro + ordenação + {@code LIMIT}) — uma única consulta em vez de
     * N EXISTS por serviço em Java.
     *
     * <p><b>Entra quem é UNAVAILABLE/DEGRADED OU tem alerta FIRING OU tem
     * incidente ativo</b> (status sozinho já aciona; alerta/incidente acionam
     * mesmo com o serviço OPERATIONAL/UNKNOWN).</p>
     *
     * <p><b>Ordenação aprovada:</b> estado (UNAVAILABLE → DEGRADED → demais)
     * → maior severidade ativa (SEV1 → SEV4, sem severidade por último) →
     * incidente ativo → nome → id. A severidade é combinada em SQL com
     * {@code LEAST} sobre as duas subqueries (alerta mais grave × incidente
     * mais grave), e o rank desconhecido vira 9 para nunca vencer SEV4.</p>
     *
     * @param projectId projeto já provado dentro do tenant
     * @param limit     máximo de linhas (20 no contrato do painel)
     */
    @Query(
            value = """
                    SELECT * FROM (
                        SELECT s.id AS "serviceId",
                               s.name AS "serviceName",
                               s.status AS "status",
                               s.status_changed_at AS "statusChangedAt",
                               (
                                   SELECT a.severity
                                   FROM alerts a
                                   WHERE a.service_id = s.id AND a.status = 'FIRING'
                                   ORDER BY CASE a.severity WHEN 'SEV1' THEN 1
                                                 WHEN 'SEV2' THEN 2
                                                 WHEN 'SEV3' THEN 3 ELSE 4 END,
                                            a.started_at DESC, a.id DESC
                                   LIMIT 1
                               ) AS "highestAlertSeverity",
                               (
                                   SELECT i.severity
                                   FROM incidents i
                                   WHERE i.service_id = s.id AND i.status <> 'RESOLVED'
                                   ORDER BY CASE i.severity WHEN 'SEV1' THEN 1
                                                 WHEN 'SEV2' THEN 2
                                                 WHEN 'SEV3' THEN 3 ELSE 4 END,
                                            i.started_at DESC, i.id DESC
                                   LIMIT 1
                               ) AS "highestIncidentSeverity",
                               EXISTS (
                                   SELECT 1
                                   FROM incidents i2
                                   WHERE i2.service_id = s.id AND i2.status <> 'RESOLVED'
                               ) AS "hasActiveIncident"
                        FROM services s
                        WHERE s.project_id = :projectId
                          AND (
                              s.status IN ('UNAVAILABLE', 'DEGRADED')
                              OR EXISTS (
                                  SELECT 1 FROM alerts a
                                  WHERE a.service_id = s.id AND a.status = 'FIRING')
                              OR EXISTS (
                                  SELECT 1 FROM incidents i
                                  WHERE i.service_id = s.id AND i.status <> 'RESOLVED')
                          )
                    ) t
                    ORDER BY CASE t."status" WHEN 'UNAVAILABLE' THEN 1
                                 WHEN 'DEGRADED' THEN 2 ELSE 3 END,
                             LEAST(
                                 CASE WHEN t."highestAlertSeverity" = 'SEV1' THEN 1
                                      WHEN t."highestAlertSeverity" = 'SEV2' THEN 2
                                      WHEN t."highestAlertSeverity" = 'SEV3' THEN 3
                                      WHEN t."highestAlertSeverity" = 'SEV4' THEN 4
                                      ELSE 9 END,
                                 CASE WHEN t."highestIncidentSeverity" = 'SEV1' THEN 1
                                      WHEN t."highestIncidentSeverity" = 'SEV2' THEN 2
                                      WHEN t."highestIncidentSeverity" = 'SEV3' THEN 3
                                      WHEN t."highestIncidentSeverity" = 'SEV4' THEN 4
                                      ELSE 9 END),
                             CASE WHEN t."hasActiveIncident" THEN 0 ELSE 1 END,
                             t."serviceName" ASC,
                             t."serviceId" ASC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<ServiceAttentionProjection> findServicesNeedingAttention(
            @Param("projectId") UUID projectId, @Param("limit") int limit);
}
