package com.sentinel.project.monitor.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sentinel.project.monitor.model.MonitorCheck;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência do histórico de checagens (Prompt 10).
 *
 * <p><b>Janela BOUNDED, nunca {@code findAll}:</b> a avaliação de estado lê
 * no máximo {@code OperationalStateEvaluator.EVALUATION_WINDOW} linhas por
 * monitor via {@link PageRequest} ({@code PageRequest.of(0, N)}) — o banco
 * ordena e limita, a aplicação nunca carrega a história inteira para decidir
 * em memória. O índice {@code idx_monitor_checks_monitor_started_desc}
 * (monitor_id, started_at DESC) cobre exatamente este plano de acesso.</p>
 *
 * <p><b>Duas leituras, duas intenções:</b></p>
 * <ul>
 *   <li>{@link #findRelevantByMonitorIdOrderByStartedAtDesc} — a JANELA de
 *       avaliação: exclui na própria query os checks
 *       {@code SENTINEL_INTERNAL_FAILURE} (falha interna do Sentinel NÃO é
 *       evidência sobre o alvo), mais recente primeiro;</li>
 *   <li>{@link #findByMonitorIdOrderByStartedAtDesc} — o HISTÓRICO exposto
 *       pela API ({@code GET .../checks}), com todas as linhas.</li>
 * </ul>
 *
 * <p>A ordenação é fixa no próprio método (mais recente primeiro), nas DUAS
 * leituras, sempre com o desempate {@code createdAt DESC}: torna a ordem
 * determinística mesmo quando o {@code Clock} de teste está congelado e dois
 * checks compartilham o mesmo {@code startedAt}. Por isso os {@link Pageable}
 * entram <b>sem sort</b> (só página/tamanho): page/size é do cliente, a ordem
 * é contrato do servidor.</p>
 *
 * <p><b>Não existe leitura global de checks:</b> toda consulta é escopada
 * por {@code monitorId}, e o {@code monitorId} só chega aqui depois de a
 * API provar a cadeia Organization → Project → Service → Monitor (nunca
 * {@code findById} avulso como base de autorização).</p>
 */
public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, UUID> {

    /**
     * Janela de avaliação: últimos {@code pageable} checks RELEVANTES do
     * monitor (outcome diferente de {@code SENTINEL_INTERNAL_FAILURE}),
     * mais recente primeiro. O count explícito evita que o Spring derive a
     * query de contagem de um JPQL com ORDER BY.
     *
     * @param monitorId monitor dono do histórico
     * @param pageable  {@code PageRequest.of(0, N)} — N = tamanho da janela
     * @return página (primeira) dos checks relevantes
     */
    @Query(
            value = """
                    SELECT c
                    FROM MonitorCheck c
                    WHERE c.monitor.id = :monitorId
                      AND c.outcome <> com.sentinel.project.monitor.model.CheckOutcome.SENTINEL_INTERNAL_FAILURE
                    ORDER BY c.startedAt DESC, c.createdAt DESC
                    """,
            countQuery = """
                    SELECT count(c)
                    FROM MonitorCheck c
                    WHERE c.monitor.id = :monitorId
                      AND c.outcome <> com.sentinel.project.monitor.model.CheckOutcome.SENTINEL_INTERNAL_FAILURE
                    """)
    Page<MonitorCheck> findRelevantByMonitorIdOrderByStartedAtDesc(
            @Param("monitorId") UUID monitorId, Pageable pageable);

    /**
     * Histórico COMPLETO do monitor para a API de leitura (inclui checks
     * internos — o cliente pode ver que uma checagem não rodou por motivo do
     * Sentinel). Ordenação mais recente primeiro com o <b>mesmo desempate da
     * janela</b> ({@code createdAt DESC}): com o {@code Clock} congelado nos
     * testes dois checks podem compartilhar o {@code startedAt} e a ordem
     * ficaria não-determinística — por isso a ordenação é EXPLÍCITA aqui e
     * não um derived query só por {@code startedAt}.
     *
     * @param monitorId monitor dono do histórico
     * @param pageable  página/tamanho — o sort é IGNORADO: a ordem é a da query
     * @return página do histórico
     */
    @Query(
            value = """
                    SELECT c
                    FROM MonitorCheck c
                    WHERE c.monitor.id = :monitorId
                    ORDER BY c.startedAt DESC, c.createdAt DESC
                    """,
            countQuery = """
                    SELECT count(c)
                    FROM MonitorCheck c
                    WHERE c.monitor.id = :monitorId
                    """)
    Page<MonitorCheck> findByMonitorIdOrderByStartedAtDesc(
            @Param("monitorId") UUID monitorId, Pageable pageable);

    // --- métricas agregadas (Prompt 11) ------------------------------------
    //
    // TODAS as leituras abaixo são AGREGAÇÕES no banco dentro da janela
    // [windowFrom, windowTo): o máximo que trafega é uma linha por bucket
    // (30 no total) + uma linha de resumo + uma linha por monitor do serviço.
    // Nenhuma delas materializa MonitorCheck e nenhuma é findAll.
    //
    // LATÊNCIA: avg/min/max de duration_millis sempre com FILTER sobre
    // outcome IN ('SUCCESS','DEGRADED_SIGNAL') — só a resposta do alvo
    // mede latência; TARGET_FAILURE e SENTINEL_INTERNAL_FAILURE ficam de
    // fora. Sem nenhum SUCCESS/DEGRADED no recorte, os três voltam null
    // (FILTER sem linha correspondente produz NULL, não 0).
    //
    // ENQUADRAMENTO DO BUCKET: floor(epoch / bucketSeconds) × bucketSeconds
    // produz um BIGINT de epoch; a grade (generate_series) produz o MESMO
    // BIGINT, então o LEFT JOIN é exato e o alinhamento independe do
    // timezone da sessão (date_trunc estaria preso ao fuso do servidor).
    // O bucket em curso fica de fora: a grade vai do primeiro ao último
    // bucket COMPLETO da janela, calculado pelo service.
    //
    // JANELA MEIA-ABERTA: started_at >= windowFrom AND started_at < windowTo
    // — um check que começa exatamente no fim da janela pertence ao período
    // seguinte.

    /**
     * Série temporal de UM monitor: todos os buckets da grade, inclusive os
     * vazios (LEFT JOIN), ordenados por início de bucket.
     *
     * @param monitorId         monitor dono do histórico (já provado pela cadeia)
     * @param windowFrom        início da janela (incluso), epoch em segundos
     * @param windowTo          fim da janela (excluso), epoch em segundos
     * @param firstBucketEpoch  início do primeiro bucket completo
     * @param lastBucketEpoch   início do último bucket completo
     * @param bucketSeconds     tamanho do bucket
     * @return exatamente {@code (lastBucketEpoch − firstBucketEpoch) /
     *         bucketSeconds + 1} linhas
     */
    @Query(
            value = """
                    WITH grid AS (
                        SELECT generate_series(
                                   :firstBucketEpoch, :lastBucketEpoch, :bucketSeconds) AS bucket_epoch
                    ),
                    agg AS (
                        SELECT
                            (floor(extract(epoch FROM c.started_at) / :bucketSeconds)
                                 * :bucketSeconds)::bigint AS bucket_epoch,
                            count(*) FILTER (WHERE c.outcome = 'SUCCESS') AS success_count,
                            count(*) FILTER (WHERE c.outcome = 'DEGRADED_SIGNAL') AS degraded_count,
                            count(*) FILTER (WHERE c.outcome = 'TARGET_FAILURE') AS target_failure_count,
                            count(*) FILTER (WHERE c.outcome = 'SENTINEL_INTERNAL_FAILURE')
                                AS sentinel_internal_failure_count,
                            avg(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS avg_duration_millis,
                            min(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS min_duration_millis,
                            max(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS max_duration_millis
                        FROM monitor_checks c
                        WHERE c.monitor_id = :monitorId
                          AND c.started_at >= :windowFrom
                          AND c.started_at < :windowTo
                        GROUP BY 1
                    )
                    SELECT g.bucket_epoch AS "bucketEpoch",
                           COALESCE(a.success_count, 0) AS "successCount",
                           COALESCE(a.degraded_count, 0) AS "degradedCount",
                           COALESCE(a.target_failure_count, 0) AS "targetFailureCount",
                           COALESCE(a.sentinel_internal_failure_count, 0)
                               AS "sentinelInternalFailureCount",
                           a.avg_duration_millis AS "avgDurationMillis",
                           a.min_duration_millis AS "minDurationMillis",
                           a.max_duration_millis AS "maxDurationMillis"
                    FROM grid g
                    LEFT JOIN agg a ON a.bucket_epoch = g.bucket_epoch
                    ORDER BY g.bucket_epoch
                    """,
            nativeQuery = true)
    List<MetricsBucketProjection> bucketRowsByMonitor(
            @Param("monitorId") UUID monitorId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo,
            @Param("firstBucketEpoch") long firstBucketEpoch,
            @Param("lastBucketEpoch") long lastBucketEpoch,
            @Param("bucketSeconds") long bucketSeconds);

    /**
     * Resumo de UM monitor na janela — agregação sem {@code GROUP BY}, sempre
     * UMA linha (contagens 0 e latência {@code null} quando não há nenhum
     * check SUCCESS/DEGRADED na janela).
     */
    @Query(
            value = """
                    SELECT count(*) FILTER (WHERE c.outcome = 'SUCCESS') AS "successCount",
                           count(*) FILTER (WHERE c.outcome = 'DEGRADED_SIGNAL') AS "degradedCount",
                           count(*) FILTER (WHERE c.outcome = 'TARGET_FAILURE') AS "targetFailureCount",
                           count(*) FILTER (WHERE c.outcome = 'SENTINEL_INTERNAL_FAILURE')
                               AS "sentinelInternalFailureCount",
                           avg(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "avgDurationMillis",
                           min(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "minDurationMillis",
                           max(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "maxDurationMillis"
                    FROM monitor_checks c
                    WHERE c.monitor_id = :monitorId
                      AND c.started_at >= :windowFrom
                      AND c.started_at < :windowTo
                    """,
            nativeQuery = true)
    MetricsSummaryProjection summaryByMonitor(
            @Param("monitorId") UUID monitorId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo);

    /**
     * Série temporal de UM serviço: a MESMA grade do monitor, mas agregando
     * os checks de TODOS os monitores do serviço (ativos e inativos — o
     * filtro é por serviço, nunca por {@code active}).
     */
    @Query(
            value = """
                    WITH grid AS (
                        SELECT generate_series(
                                   :firstBucketEpoch, :lastBucketEpoch, :bucketSeconds) AS bucket_epoch
                    ),
                    agg AS (
                        SELECT
                            (floor(extract(epoch FROM c.started_at) / :bucketSeconds)
                                 * :bucketSeconds)::bigint AS bucket_epoch,
                            count(*) FILTER (WHERE c.outcome = 'SUCCESS') AS success_count,
                            count(*) FILTER (WHERE c.outcome = 'DEGRADED_SIGNAL') AS degraded_count,
                            count(*) FILTER (WHERE c.outcome = 'TARGET_FAILURE') AS target_failure_count,
                            count(*) FILTER (WHERE c.outcome = 'SENTINEL_INTERNAL_FAILURE')
                                AS sentinel_internal_failure_count,
                            avg(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS avg_duration_millis,
                            min(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS min_duration_millis,
                            max(c.duration_millis)
                                FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                                AS max_duration_millis
                        FROM monitor_checks c
                        JOIN monitors m ON m.id = c.monitor_id
                        WHERE m.service_id = :serviceId
                          AND c.started_at >= :windowFrom
                          AND c.started_at < :windowTo
                        GROUP BY 1
                    )
                    SELECT g.bucket_epoch AS "bucketEpoch",
                           COALESCE(a.success_count, 0) AS "successCount",
                           COALESCE(a.degraded_count, 0) AS "degradedCount",
                           COALESCE(a.target_failure_count, 0) AS "targetFailureCount",
                           COALESCE(a.sentinel_internal_failure_count, 0)
                               AS "sentinelInternalFailureCount",
                           a.avg_duration_millis AS "avgDurationMillis",
                           a.min_duration_millis AS "minDurationMillis",
                           a.max_duration_millis AS "maxDurationMillis"
                    FROM grid g
                    LEFT JOIN agg a ON a.bucket_epoch = g.bucket_epoch
                    ORDER BY g.bucket_epoch
                    """,
            nativeQuery = true)
    List<MetricsBucketProjection> bucketRowsByService(
            @Param("serviceId") UUID serviceId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo,
            @Param("firstBucketEpoch") long firstBucketEpoch,
            @Param("lastBucketEpoch") long lastBucketEpoch,
            @Param("bucketSeconds") long bucketSeconds);

    /**
     * Resumo de UM serviço na janela — checks de todos os monitores do
     * serviço, sempre UMA linha.
     */
    @Query(
            value = """
                    SELECT count(*) FILTER (WHERE c.outcome = 'SUCCESS') AS "successCount",
                           count(*) FILTER (WHERE c.outcome = 'DEGRADED_SIGNAL') AS "degradedCount",
                           count(*) FILTER (WHERE c.outcome = 'TARGET_FAILURE') AS "targetFailureCount",
                           count(*) FILTER (WHERE c.outcome = 'SENTINEL_INTERNAL_FAILURE')
                               AS "sentinelInternalFailureCount",
                           avg(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "avgDurationMillis",
                           min(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "minDurationMillis",
                           max(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "maxDurationMillis"
                    FROM monitor_checks c
                    JOIN monitors m ON m.id = c.monitor_id
                    WHERE m.service_id = :serviceId
                      AND c.started_at >= :windowFrom
                      AND c.started_at < :windowTo
                    """,
            nativeQuery = true)
    MetricsSummaryProjection summaryByService(
            @Param("serviceId") UUID serviceId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo);

    /**
     * Breakdown por monitor de UM serviço na janela: uma linha por monitor
     * que teve check no período, ordenada por nome (únicos por serviço —
     * ordem determinística).
     */
    @Query(
            value = """
                    SELECT c.monitor_id AS "monitorId",
                           m.name AS "monitorName",
                           count(*) FILTER (WHERE c.outcome = 'SUCCESS') AS "successCount",
                           count(*) FILTER (WHERE c.outcome = 'DEGRADED_SIGNAL') AS "degradedCount",
                           count(*) FILTER (WHERE c.outcome = 'TARGET_FAILURE') AS "targetFailureCount",
                           count(*) FILTER (WHERE c.outcome = 'SENTINEL_INTERNAL_FAILURE')
                               AS "sentinelInternalFailureCount",
                           avg(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "avgDurationMillis",
                           min(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "minDurationMillis",
                           max(c.duration_millis)
                               FILTER (WHERE c.outcome IN ('SUCCESS','DEGRADED_SIGNAL'))
                               AS "maxDurationMillis"
                    FROM monitor_checks c
                    JOIN monitors m ON m.id = c.monitor_id
                    WHERE m.service_id = :serviceId
                      AND c.started_at >= :windowFrom
                      AND c.started_at < :windowTo
                    GROUP BY c.monitor_id, m.name
                    ORDER BY m.name
                    """,
            nativeQuery = true)
    List<ServiceMetricsBreakdownProjection> breakdownRowsByService(
            @Param("serviceId") UUID serviceId,
            @Param("windowFrom") Instant windowFrom,
            @Param("windowTo") Instant windowTo);
}
