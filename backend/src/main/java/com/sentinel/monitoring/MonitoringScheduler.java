package com.sentinel.monitoring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loop de checagens do Prompt 09 — ponte entre o tempo (agendador Spring) e
 * o trabalho (pool do {@link MonitoringDispatcher}).
 *
 * <p><b>Tick curto:</b> {@link #tick()} só consulta elegíveis, limpa o
 * {@link DueTracker}, decide quais monitores estão vencidos e despacha.
 * Nenhum HTTP acontece nesta thread — cada checagem roda na thread do pool
 * que o dispatcher criou.</p>
 *
 * <p><b>Commit somente em aceite</b> (correção do Prompt 09): o novo
 * horário é gravado APENAS quando o despacho é {@code ACCEPTED}. Se o
 * dispatcher responder {@code IN_FLIGHT} ou {@code SATURATED}, o monitor
 * permanece <em>due</em> e é retentado no próximo tick. A checagem de
 * em-voo é feita <b>dentro</b> do dispatcher, antes de qualquer commit —
 * nunca por flags do scheduler.</p>
 *
 * <p><b>Isolamento de falha por monitor</b> ({@link #tick()}): uma
 * exceção em um monitor (bug de leitura, id nulo, etc.) não aborta o lote:
 * é contada e logada, e o próximo monitor continua sendo avaliado.
 * Exceções que escapam de um <em>intero</em> tick são engolidas pelo
 * {@link #scheduledTick()} para que o agendador Spring nunca desarme a
 * task — o alarme é o contador + log, não o crash do loop.</p>
 *
 * <p><b>Perfil de teste:</b> o {@code @Scheduled} só existe quando
 * {@code sentinel.monitoring.enabled=true} (ver
 * {@link MonitoringSchedulingConfiguration}); nos testes de unidade chama-se
 * {@link #tick()} diretamente.</p>
 */
@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final MonitorEligibilityService eligibilityService;
    private final DueTracker dueTracker;
    private final MonitoringDispatcher dispatcher;
    private final Clock clock;
    private final MonitoringProperties properties;

    public MonitoringScheduler(
            MonitorEligibilityService eligibilityService,
            DueTracker dueTracker,
            MonitoringDispatcher dispatcher,
            Clock clock,
            MonitoringProperties properties) {
        this.eligibilityService = eligibilityService;
        this.dueTracker = dueTracker;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Entrada do agendador Spring — roda a cada
     * {@code sentinel.monitoring.scheduler-delay-ms}. Desligada
     * ({@code early-return}) quando o engine está pausado via propriedade.
     */
    @Scheduled(fixedDelayString = "${sentinel.monitoring.scheduler-delay-ms:10000}")
    public void scheduledTick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            tick();
        }
        catch (RuntimeException ex) {
            // O loop nunca morre: um tick com falha é logado e o próximo
            // tick roda normalmente. Alerta vem do log/contador, não de crash.
            log.error("monitoring tick failed", ex);
        }
    }

    /**
     * Núcleo do tick — público para os testes (perfil test desliga o
     * {@code @Scheduled} via propriedade, então a chamada é manual).
     *
     * @return quantidade de monitores cujo despacho foi ACEITO neste tick
     */
    public int tick() {
        List<Monitor> eligible = eligibilityService.findEligibleMonitors();
        Set<UUID> eligibleIds = new HashSet<>();
        for (Monitor monitor : eligible) {
            eligibleIds.add(monitor.getId());
        }

        // Limpeza: quem saiu da elegibilidade (desativado, removido, serviço
        // ou projeto inativos) perde o estado de vencimento.
        int pruned = dueTracker.retainOnly(eligibleIds);
        if (pruned > 0) {
            log.debug("monitoring pruned obsolete due-state count={}", pruned);
        }

        int accepted = 0;
        for (Monitor monitor : eligible) {
            try {
                accepted += dispatchIfDue(monitor);
            }
            catch (RuntimeException ex) {
                // Isolamento por monitor: bug com um monitor não aborta o lote.
                log.error("monitor skipped during tick monitorId={}", monitor.getId(), ex);
            }
        }
        return accepted;
    }

    /**
     * Avalia e, se vencido, despacha UM monitor. A ordem é material para a
     * correção do Prompt 09: <b>isDue → tryDispatch → commit</b> — o commit
     * só existe para {@code ACCEPTED}.
     *
     * @param monitor monitor elegível
     * @return 1 se aceito, 0 caso contrário
     */
    private int dispatchIfDue(Monitor monitor) {
        if (!dueTracker.isDue(monitor, clock.instant())) {
            return 0;
        }
        MonitoringDispatcher.DispatchOutcome outcome = dispatcher.tryDispatch(monitor);
        if (outcome == MonitoringDispatcher.DispatchOutcome.ACCEPTED) {
            // Único ponto que adia o próximo vencimento. IN_FLIGHT e
            // SATURATED NÃO chegam aqui: o monitor segue due.
            dueTracker.commit(monitor, clock.instant());
            return 1;
        }
        return 0;
    }
}
