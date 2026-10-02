package com.sentinel.project.alert.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de alertas disparados (Prompt 13) — quarto nível da hierarquia
 * Organization → Project → Service → Alert.
 *
 * <p><b>Anti-IDOR mesmo padrão do {@code AlertRuleRepository}:</b> a cadeia do
 * tenant prova Organization → Project → Service e TODA leitura daqui é
 * escopada por {@code serviceId} — nunca existe {@code findById} avulso como
 * base de autorização. O detalhe ({@link #findByServiceIdAndId}) prova
 * Service → Alert na própria consulta.</p>
 *
 * <p><b>Ordem fixa da listagem:</b> {@code startedAt DESC, id DESC} — "mais
 * recente primeiro" com desempate determinístico no id, imposta pelo service
 * via {@link Pageable} (mesma convenção de allowlist/desempate do
 * {@code AlertRuleService}). O índice {@code idx_alerts_started_at_desc} da
 * V10 cobre o caso sem filtro.</p>
 *
 * <p><b>Filtros opcionais status/severity</b> ({@code (:p IS NULL OR ...)}):
 * um único {@code @Query} em vez de 4 derived queries — parâmetro nulo não
 * restringe a linha, e enum inválido é rejeitado com 400 ANTES de chegar
 * aqui (bind de enum JPA falharia).</p>
 *
 * <p><b>Leituras do engine</b> ({@link #findByServiceIdAndStatus}) trazem os
 * alertas ABERTOS de uma vez — o {@code AlertEngine} cruza esse conjunto com
 * as regras ativas em memória para decidir dedup/touch/RULE_DEACTIVATED sem
 * N consultas por regra.</p>
 */
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** Lookup do detalhe: prova Service → Alert. Nunca findById avulso. */
    Optional<Alert> findByServiceIdAndId(UUID serviceId, UUID alertId);

    /**
     * Listagem paginada por serviço com os dois filtros opcionais da API.
     *
     * <p>{@code JOIN FETCH} na regra: a resposta expõe {@code ruleName} e o
     * snapshot do alerta mora na própria linha, mas o nome vem da regra — sem
     * o fetch seriam N selects por página (N+1). Como o alvo é
     * {@code @ManyToOne}, o fetch não quebra a paginação (sem duplicação de
     * linha).</p>
     *
     * @param serviceId serviço dono (já provado na cadeia do tenant)
     * @param status    filtro opcional por status (FIRING/RESOLVED)
     * @param severity  filtro opcional por severidade (SEV1–SEV4)
     */
    @Query("""
            SELECT a
            FROM Alert a
            JOIN FETCH a.alertRule r
            WHERE a.service.id = :serviceId
              AND (:status IS NULL OR a.status = :status)
              AND (:severity IS NULL OR a.severity = :severity)
            """)
    Page<Alert> search(
            @Param("serviceId") UUID serviceId,
            @Param("status") AlertStatus status,
            @Param("severity") AlertSeverity severity,
            Pageable pageable);

    /**
     * Alerta ainda ABERTO de UMA regra — pré-check de deduplicação do engine
     * (o índice parcial UNIQUE da V10 é a linha de defesa sob corrida) e
     * alvo da resolução por regra (AUTO / RULE_DEACTIVATED).
     */
    Optional<Alert> findByAlertRuleIdAndStatus(UUID alertRuleId, AlertStatus status);

    /**
     * Alertas em um dado status de um serviço — o engine usa com
     * {@link AlertStatus#FIRING} para dedup/touch/RULE_DEACTIVATED e o
     * lifecycle usa para resolver tudo com {@code PARENT_INACTIVE} quando o
     * serviço é desativado.
     */
    List<Alert> findByServiceIdAndStatus(UUID serviceId, AlertStatus status);

    /**
     * Alertas abertos de TODOS os serviços de UM projeto — resolução em massa
     * com {@code PARENT_INACTIVE} quando o projeto é desativado.
     */
    @Query("""
            SELECT a
            FROM Alert a
            WHERE a.service.project.id = :projectId
              AND a.status = :status
            """)
    List<Alert> findByProjectIdAndStatus(
            @Param("projectId") UUID projectId, @Param("status") AlertStatus status);

    /**
     * Ids das regras com alerta ABERTO num serviço — o engine cruza este
     * conjunto com as regras ativas em memória para achar as desativadas
     * (resolução RULE_DEACTIVATED) sem materializar entidade nenhuma.
     */
    @Query("""
            SELECT a.alertRule.id
            FROM Alert a
            WHERE a.service.id = :serviceId
              AND a.status = :status
            """)
    List<UUID> findOpenRuleIds(
            @Param("serviceId") UUID serviceId, @Param("status") AlertStatus status);
}
