package com.sentinel.project.alert.repository;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de regras de alerta (Prompt 12) — quarto nível da hierarquia
 * Organization → Project → Service → AlertRule, com escopo opcional de
 * Monitor.
 *
 * <p><b>Por que a cadeia é provada em TRÊS consultas:</b> mesmo racional do
 * {@code MonitorRepository} — a autorização não pode ser derived query em
 * {@code AlertRule} (a entidade só conhece {@code service}), então o lookup
 * canônico é composto:</p>
 * <ol>
 *   <li>{@code ProjectRepository.findByOrganizationIdAndId(orgId, projectId)}
 *       prova <b>Organization → Project</b>;</li>
 *   <li>{@code ServiceRepository.findByProjectIdAndId(projectId, serviceId)}
 *       prova <b>Project → Service</b>;</li>
 *   <li>{@link #findByServiceIdAndId(serviceId, ruleId)} prova
 *       <b>Service → AlertRule</b>.</li>
 * </ol>
 * <p>Como o serviço já foi resolvido dentro do tenant, todo o resto do
 * repositório é escopado apenas por {@code serviceId} — não existe caminho de
 * leitura global de regras e {@code findById} avulso nunca é usado como base
 * de autorização (anti-IDOR).</p>
 *
 * <p><b>Listagem com 5 filtros opcionais</b> (active, type, severity,
 * monitorId, name): em vez de 32 derived queries (2⁵ combinações), existe UM
 * {@code @Query} com predicados opcionais ({@code (:p IS NULL OR campo = :p)}).
 * Cada parâmetro nulo simplesmente não restringe a linha. A ordenação vem no
 * {@link Pageable} já validado pelo service (allowlist + desempate em
 * {@code id asc}).</p>
 *
     * <p>O filtro de nome caza contra {@code nameNormalized} (coluna em
     * minúsculas), então a busca é case-insensitive por construção — o pattern
     * ({@code %texto%}) é montado no service com o MESMO algoritmo da coluna.
     * O pattern vem pronto (sem {@code CONCAT} na JPQL): com {@code CONCAT} o
     * Hibernate 7 infere o tipo do parâmetro como {@code bytea} e o PostgreSQL
     * rejeita {@code varchar ~~ bytea} na execução (500 na listagem).</p>
     */
public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    /** Lookup do detalhe: prova Service → AlertRule. Nunca findById avulso. */
    Optional<AlertRule> findByServiceIdAndId(UUID serviceId, UUID ruleId);

    /** Pré-check de unicidade (create) — 409 imediato, antes do banco. */
    boolean existsByServiceIdAndNameNormalized(UUID serviceId, String nameNormalized);

    /** Pré-check de unicidade do rename (update) — exclui a própria regra. */
    boolean existsByServiceIdAndNameNormalizedAndIdNot(
            UUID serviceId, String nameNormalized, UUID ruleId);

    /**
     * Listagem paginada com os cinco filtros opcionais da API. Cada filtro é
     * aplicado somente quando não-nulo ({@code :p IS NULL} é sempre true e a
     * coluna não é comparada), então a chamada sem filtros devolve todas as
     * regras do serviço.
     *
     * <p>{@code monitorId} presente restringe ao escopo daquele monitor;
     * ausente deixa todos os escopos (Service-level e Monitor-level) — não
     * há "filtro de service-level": a ausência do parâmetro já é a consulta
     * ampla.</p>
     *
     * @param serviceId serviço dono (já provado na cadeia do tenant)
     * @param active    filtro opcional por participação no catálogo
     * @param type      filtro opcional por tipo de condição
     * @param severity  filtro opcional por severidade
     * @param monitorId filtro opcional por escopo de monitor
     * @param search    pattern LIKE pronto ({@code %texto%}) ou {@code null}
     */
    @Query("""
            SELECT a
            FROM AlertRule a
            WHERE a.service.id = :serviceId
              AND (:active IS NULL OR a.active = :active)
              AND (:type IS NULL OR a.type = :type)
              AND (:severity IS NULL OR a.severity = :severity)
              AND (:monitorId IS NULL OR a.monitor.id = :monitorId)
              AND (:search IS NULL OR a.nameNormalized LIKE :search)
            """)
    Page<AlertRule> search(
            @Param("serviceId") UUID serviceId,
            @Param("active") Boolean active,
            @Param("type") AlertRuleType type,
            @Param("severity") AlertSeverity severity,
            @Param("monitorId") UUID monitorId,
            @Param("search") String search,
            Pageable pageable);
}
