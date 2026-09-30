package com.sentinel.project.monitor.repository;

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
}
