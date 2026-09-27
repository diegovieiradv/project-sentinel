package com.sentinel.project.repository;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.model.Project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de projetos (Prompt 06).
 *
 * <p>Toda consulta é <b>escopada por organização</b>: não existe caminho de
 * leitura global de projetos, e {@link #findByOrganizationIdAndId} é o lookup
 * canônico do detalhe — o projeto de OUTRA organização simplesmente não
 * resolve, virando o mesmo 404 genérico de um id inexistente
 * (anti-enumeração). {@code findById} + checagem manual nunca é usado.</p>
 *
 * <p>As variantes de listagem variam apenas nos filtros opcionais (status e
 * busca por nome) e recebem o {@link Pageable} já montado pelo service com a
 * ordenação determinística <b>createdAt asc, id asc</b> — o id como desempate
 * garante a mesma página em toda requisição mesmo quando dois projetos têm o
 * mesmo timestamp.</p>
 *
 * <p>{@code ...NameNormalizedContaining} casa contra a coluna normalizada
 * (minúsculas), então a busca por nome é case-insensitive por construção,
 * sem {@code LOWER(coluna)} na query (que não usaria o índice).</p>
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** Lookup canônico do detalhe: escopo tenant + id, nunca findById avulso. */
    Optional<Project> findByOrganizationIdAndId(UUID organizationId, UUID projectId);

    /** Pré-check de unicidade (create) — 409 imediato, antes do banco. */
    boolean existsByOrganizationIdAndNameNormalized(UUID organizationId, String nameNormalized);

    /** Pré-check de unicidade do rename (update) — exclui o próprio projeto. */
    boolean existsByOrganizationIdAndNameNormalizedAndIdNot(
            UUID organizationId, String nameNormalized, UUID projectId);

    Page<Project> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<Project> findByOrganizationIdAndActive(UUID organizationId, boolean active, Pageable pageable);

    Page<Project> findByOrganizationIdAndNameNormalizedContaining(
            UUID organizationId, String nameNormalized, Pageable pageable);

    Page<Project> findByOrganizationIdAndActiveAndNameNormalizedContaining(
            UUID organizationId, boolean active, String nameNormalized, Pageable pageable);
}
