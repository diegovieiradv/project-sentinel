package com.sentinel.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sentinel.project.monitor.model.Monitor;

import org.springframework.stereotype.Component;

/**
 * Controle de vencimento dos monitores (Prompt 09) — em memória, por
 * instância de aplicação.
 *
 * <p><b>Modelo:</b> {@code ConcurrentHashMap<monitorId, próximaExecução>}.
 * Entrada <b>ausente</b> = primeira execução, vencida de imediato (monitor
 * recém-criado ou reativado não espera o intervalo para a primeira checagem).
 * Entrada presente = vencida quando {@code now >= próximaExecução}.</p>
 *
 * <p><b>Commit só em dispatch ACEITO</b> (correção do Prompt 09):
 * {@link #isDue} apenas <em>computa</em> se o monitor está vencido; o novo
 * horário ({@code agora + intervalSeconds}) é gravado por {@link #commit}
 * somente quando o {@link MonitoringDispatcher} aceitou o trabalho. Se o
 * dispatch for recusado por saturação, ou o monitor estiver em voo
 * ({@code IN_FLIGHT}), nada é gravado — o monitor continua <em>due</em> e é
 * retentado no próximo tick. A checagem de "em voo" acontece dentro do
 * dispatcher, ANTES e de forma independente do commit de vencimento.</p>
 *
 * <p><b>Limpeza de estado obsoleto</b> ({@link #retainOnly}): a cada tick o
 * scheduler restringe o mapa aos monitores elegíveis do momento. Monitor
 * desativado (ou com serviço/projeto desativado) perde a entrada e, ao ser
 * reativado, volta a executar imediatamente. Mudança de intervalo vale a
 * partir do próximo {@link #commit}, que sempre usa o intervalo ATUAL do
 * monitor.</p>
 *
 * <p><b>Concorrência:</b> operações sobre {@code ConcurrentHashMap} são
 * atômicas por chave; o scheduler roda em uma única thread de tick, mas o
 * mapa é thread-safe por construção (e inspecionável por testes sem
 * sincronização adicional).</p>
 */
@Component
public class DueTracker {

    private final ConcurrentHashMap<UUID, Instant> nextExecutionAt = new ConcurrentHashMap<>();

    /**
     * Computa se o monitor está vencido — <b>não altera estado</b>.
     *
     * @param monitor monitor a avaliar
     * @param now     instante atual (relógio injetável)
     * @return {@code true} se nunca executou ou se a próxima execução já venceu
     */
    public boolean isDue(Monitor monitor, Instant now) {
        Instant next = nextExecutionAt.get(monitor.getId());
        return next == null || !now.isBefore(next);
    }

    /**
     * Grava a próxima execução = {@code agora + intervalSeconds ATUAL do
     * monitor}. Chamado apenas quando o dispatcher ACEITOU o dispatch.
     *
     * @param monitor monitor cujo dispatch foi aceito
     * @param now     instante do aceite (tick atual)
     */
    public void commit(Monitor monitor, Instant now) {
        nextExecutionAt.put(
                monitor.getId(), now.plus(Duration.ofSeconds(monitor.getIntervalSeconds())));
    }

    /**
     * Remove entradas de monitores que não estão mais no conjunto de
     * elegíveis (desativados, removidos, serviço/projeto inativo).
     *
     * @param eligibleMonitorIds ids elegíveis deste tick
     * @return quantas entradas obsoletas foram removidas
     */
    public int retainOnly(Collection<UUID> eligibleMonitorIds) {
        Set<UUID> keep = new HashSet<>(eligibleMonitorIds);
        int before = nextExecutionAt.size();
        nextExecutionAt.keySet().retainAll(keep);
        return before - nextExecutionAt.size();
    }

    /** Inspeciona o tracker (testes e diagnóstico). */
    public boolean isTracked(UUID monitorId) {
        return nextExecutionAt.containsKey(monitorId);
    }

    /** Quantidade de monitores com horário gravado (testes e diagnóstico). */
    public int size() {
        return nextExecutionAt.size();
    }

    /** Próxima execução gravada, se houver (testes e diagnóstico). */
    public Instant nextExecutionAt(UUID monitorId) {
        return nextExecutionAt.get(monitorId);
    }
}
