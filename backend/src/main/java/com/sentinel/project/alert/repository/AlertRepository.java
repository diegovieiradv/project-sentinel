package com.sentinel.project.alert.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.incident.model.Incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // --- automação Alert → Incident (Prompt 15) -----------------------------

    /**
     * Ids dos alertas <b>elegíveis</b> para a automação de UM serviço: aberto
     * ({@code FIRING}), ainda sem incidente e cuja regra pediu
     * {@code autoCreateIncident} — exatamente o que a reconciliação
     * ({@code AlertAutomationReconciler}) precisa para "relembrar" um
     * disparo que nasceu enquanto a flag estava desligada (ou que perdeu o
     * evento por falha transitória).
     *
     * <p><b>Por que IDs e não entidades:</b> o reconciler não tem
     * transação própria; ele devolve IDs e chama a automação UM alerta por
     * vez, cada uma na SUA transação (isolamento por alerta — um alerta que
     * falha não derruba os demais). A elegância completa (pais ativos,
     * releitura da flag) é rechecada DENTRO dessa transação, nunca só aqui:
     * esta query é pré-check barato, não autoridade.</p>
     *
     * <p><b>Por que é naturalmente limitada:</b> o engine garante no máximo
     * UM alerta aberto por regra (deduplicação), então o resultado tem no
     * máximo quantas regras opt-in o serviço tem — e na prática 0 ou 1.
     * Ordenação fixa ({@code startedAt, id}) para o loop ser determinístico.</p>
     *
     * @param serviceId serviço avaliado (a chamada do hook carrega dele)
     */
    @Query("""
            SELECT a.id
            FROM Alert a
            WHERE a.service.id = :serviceId
              AND a.status = :status
              AND a.incident IS NULL
              AND a.alertRule.autoCreateIncident = true
            ORDER BY a.startedAt ASC, a.id ASC
            """)
    List<UUID> findOpenEligibleForAutomation(
            @Param("serviceId") UUID serviceId, @Param("status") AlertStatus status);

    // --- associação Incident ↔ Alert (Prompt 14) ----------------------------

    /**
     * Vincula o alerta a UM incidente <b>condicionalmente</b> ({@code WHERE
     * incident IS NULL}) — o coração da cardinalidade "um alerta pertence a
     * no máximo um incidente".
     *
     * <p><b>Por que UPDATE bulk em vez de setter:</b> escrever
     * {@code alert.setIncident(...)} regravaria TODA a linha — e o
     * {@code @PreUpdate} dispararia {@code updatedAt}, fazendo o alerta
     * "mexer" num timestamp que só deveria mudar quando a avaliação muda.
     * O UPDATE toca unicamente {@code incident_id}, então o alerta está
     * <b>byte-idêntico</b> depois do link (critérios de imutabilidade do
     * alerta), e a barreira da aplicação é o próprio
     * {@code UPDATE ... WHERE incident IS NULL} condicional combinado com
     * a releitura/classificação em {@code 0 linhas}: o IncidentService
     * responde {@code 200 idempotente} (mesmo incidente) ou {@code 409
     * ALERT_ALREADY_LINKED} (outro) — nunca dois vínculos. O índice
     * {@code idx_alerts_incident} da V11 é <b>não-único</b> e serve só à
     * listagem.</p>
     *
     * <p><b>0 linhas ≠ erro:</b> significa que outro vínculo venceu a
     * corrida (ou a operação já era um no-op) — o IncidentService relê o
     * alerta e responde 200 idempotente (mesmo incidente), 409
     * {@code ALERT_ALREADY_LINKED} (outro incidente) ou retenta. NUNCA
     * propaga SQL para o cliente.</p>
     *
     * <p>{@code clearAutomatically}: a entidade Alert em memória fica
     * desatualizada depois do bulk update — limpar o contexto garante que a
     * releitura devolva o {@code incident_id} verdadeiro.</p>
     *
     * @return 1 quando ESTA chamada fez o vínculo; 0 quando ele já existia
     *         (próprio ou de outro incidente)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Alert a
            SET a.incident = :incident
            WHERE a.id = :alertId
              AND a.incident IS NULL
            """)
    int linkIncident(@Param("alertId") UUID alertId, @Param("incident") Incident incident);

    /**
     * Remove o vínculo <b>condicionalmente</b> ({@code WHERE incident =
     * :incident}): só desassocia se o alerta ainda pertence AQUELE
     * incidente. Mesmo racional do link — a escrita é atômica por linha,
     * o alerta em si não é tocado em mais nada (a mensagem do FIRING, os
     * snapshots, status e timestamps ficam intactos) e 0 linhas significa
     * que outra remoção venceu a corrida (no-op, sem evento).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Alert a
            SET a.incident = null
            WHERE a.id = :alertId
              AND a.incident = :incident
            """)
    int unlinkIncident(@Param("alertId") UUID alertId, @Param("incident") Incident incident);

    /**
     * Alertas já associados ao incidente, mais recentes primeiro (mesma
     * ordem da listagem de alertas do serviço, imposta pelo {@link Pageable}).
     * {@code JOIN FETCH} na regra pelo mesmo motivo da {@link #search}: a
     * resposta lê o nome da regra e sem o fetch seriam N selects (N+1) para
     * poucos alertas.
     *
     * <p><b>Paginação no banco</b> (mesmo arranjo do {@link #search}): o GET
     * {@code .../incidents/{id}/alerts} aceita {@code page}/{@code size} como
     * o resto da API, e a cardinalidade "um incidente × N alertas" não tem
     * teto garantido — buscar a lista inteira para descartar a maior parte
     * seria uma decisão de escala tomada por engano. O derived count gerado
     * pelo Spring Data remove o {@code join fetch} (junção 1:1, mesma
     * contagem).</p>
     *
     * @param incidentId incidente dono (já provado na cadeia do tenant)
     * @param pageable   {@code startedAt desc, id desc} (ordem da API)
     */
    @Query("""
            SELECT a
            FROM Alert a
            JOIN FETCH a.alertRule r
            WHERE a.incident.id = :incidentId
            """)
    Page<Alert> searchByIncidentId(@Param("incidentId") UUID incidentId, Pageable pageable);
}
