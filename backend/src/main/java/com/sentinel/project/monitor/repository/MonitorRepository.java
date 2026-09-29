package com.sentinel.project.monitor.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistência de monitores (Prompt 08).
 *
 * <p><b>Por que a cadeia é provada em TRÊS consultas:</b> a autorização
 * Organization → Project → Service → Monitor não pode ser uma query derived
 * {@code findByOrganizationId...} em {@code Monitor} — a entidade só conhece
 * {@code service} (o caminho seria {@code service.project.organization.id}).
 * O lookup canônico portanto é composto:</p>
 * <ol>
 *   <li>{@code ProjectRepository.findByOrganizationIdAndId(orgId, projectId)}
 *       prova <b>Organization → Project</b>;</li>
 *   <li>{@code ServiceRepository.findByProjectIdAndId(projectId, serviceId)}
 *       prova <b>Project → Service</b>;</li>
 *   <li>{@link #findByServiceIdAndId(serviceId, monitorId)} prova
 *       <b>Service → Monitor</b>.</li>
 * </ol>
 * <p>Como o serviço já foi resolvido dentro do tenant, todo o resto do
 * repositório pode ser escopado apenas por {@code serviceId} — não existe
 * caminho de leitura global de monitores e {@code findById} avulso nunca é
 * usado como base de autorização.</p>
 *
 * <p>As variantes de listagem variam nos filtros opcionais (active, method e
 * busca por nome) e recebem o {@link Pageable} já montado pelo service com a
 * ordenação determinística <b>createdAt asc, id asc</b>.</p>
 *
 * <p>{@code ...NameNormalizedContaining} casa contra a coluna normalizada
 * (minúsculas), então a busca por nome é case-insensitive por construção,
 * sem {@code LOWER(coluna)} na query.</p>
 */
public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    /** Lookup do detalhe: prova Service → Monitor. Nunca findById avulso. */
    Optional<Monitor> findByServiceIdAndId(UUID serviceId, UUID monitorId);

    /** Pré-check de unicidade (create) — 409 imediato, antes do banco. */
    boolean existsByServiceIdAndNameNormalized(UUID serviceId, String nameNormalized);

    /** Pré-check de unicidade do rename (update) — exclui o próprio monitor. */
    boolean existsByServiceIdAndNameNormalizedAndIdNot(
            UUID serviceId, String nameNormalized, UUID monitorId);

    // --- listagem: active × method × name (8 combinações de filtro) --------

    Page<Monitor> findByServiceId(UUID serviceId, Pageable pageable);

    Page<Monitor> findByServiceIdAndActive(UUID serviceId, boolean active, Pageable pageable);

    Page<Monitor> findByServiceIdAndMethod(
            UUID serviceId, MonitorMethod method, Pageable pageable);

    Page<Monitor> findByServiceIdAndActiveAndMethod(
            UUID serviceId, boolean active, MonitorMethod method, Pageable pageable);

    Page<Monitor> findByServiceIdAndNameNormalizedContaining(
            UUID serviceId, String nameNormalized, Pageable pageable);

    Page<Monitor> findByServiceIdAndActiveAndNameNormalizedContaining(
            UUID serviceId, boolean active, String nameNormalized, Pageable pageable);

    Page<Monitor> findByServiceIdAndMethodAndNameNormalizedContaining(
            UUID serviceId, MonitorMethod method, String nameNormalized, Pageable pageable);

    Page<Monitor> findByServiceIdAndActiveAndMethodAndNameNormalizedContaining(
            UUID serviceId,
            boolean active,
            MonitorMethod method,
            String nameNormalized,
            Pageable pageable);

    // --- Monitoring Engine (Prompt 09) -------------------------------------

    /**
     * Monitores executáveis no tick atual do
     * {@code com.sentinel.monitoring.MonitoringScheduler} — o filtro é
     * ÚNICO e autoritativo (nada é filtrado em memória):
     *
     * <ul>
     *   <li>{@code m.active} (catálogo), {@code s.active} (serviço) e
     *       {@code p.active} (projeto) — a organização não tem coluna
     *       {@code active}, o ownership continua sendo o relacionamento
     *       {@code Monitor → Service → Project → Organization};</li>
     *   <li>{@code type = HTTP} (invariante do cadastro — outros tipos
     *       ainda não existem, mas a execução não os pegaria por engano);</li>
     *   <li>faixas coerentes: {@code intervalSeconds > 0},
     *       {@code timeoutMillis > 0} e {@code timeoutMillis < intervalSeconds
     *       * 1000} (uma checagem nunca dura mais que o próprio intervalo,
     *       senão o pipeline enfileiraria sem parar).</li>
     * </ul>
     *
     * <p>O {@code JOIN FETCH} percorre exatamente o caminho de escrita da
     * checagem ({@code service → project → organization}), evitando N+1
     * quando o executor lê {@code monitor.getService()} para o log de
     * {@code serviceId}. É leitura de sistema interno, sem paginação e sem
     * dados de usuário — o conjunto é limitado pelos monitores ativos do
     * deployment.</p>
     */
    @Query("""
            SELECT m
            FROM Monitor m
            JOIN FETCH m.service s
            JOIN FETCH s.project p
            JOIN FETCH p.organization o
            WHERE m.active = true
              AND s.active = true
              AND p.active = true
              AND m.type = com.sentinel.project.monitor.model.MonitorType.HTTP
              AND m.intervalSeconds > 0
              AND m.timeoutMillis > 0
              AND m.timeoutMillis < m.intervalSeconds * 1000
            """)
    List<Monitor> findAllEligibleForExecution();
}
