package com.sentinel.project.monitor.repository;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
