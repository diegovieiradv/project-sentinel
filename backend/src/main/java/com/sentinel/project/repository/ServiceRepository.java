package com.sentinel.project.repository;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
