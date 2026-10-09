package com.sentinel.monitoring;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import com.sentinel.alerting.AlertTrigger;
import com.sentinel.project.monitor.model.Monitor;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Despacho assíncrono das checagens (Prompt 09) — o ponto em que o tick
 * curto do {@link MonitoringScheduler} devolve a thread agendada.
 *
 * <p><b>Arquitetura:</b> um {@code ThreadPoolTaskExecutor} DEDICADO (não o
 * {@code taskExecutor} genérico) com {@code worker-count} threads, fila de
 * {@code queue-capacity} e prefixo {@code sentinel-monitoring-}. O scheduler
 * nunca executa HTTP: ele só decide <em>quando</em> despachar; o custo de
 * rede acontece nestas threads.</p>
 *
 * <p><b>Guarda de in-flight (nunca em paralelo):</b> o conjunto
 * {@code inFlight} recebe a entrada <b>antes</b> de submeter e é removido em
 * {@code finally} pela task. Como {@code Set.add} é atômico, dois ticks (ou
 * um tick reentrante) nunca despacham o mesmo monitor ao mesmo tempo; se
 * estiver em voo, o dispatch devolve {@link DispatchOutcome#IN_FLIGHT} e o
 * monitor permanece <em>due</em>.</p>
 *
 * <p><b>Backpressure:</b> fila cheia (ou pool em shutdown) ⇒
 * {@code RejectedExecutionException} ⇒ {@link DispatchOutcome#SATURATED}:
 * o in-flight é desfeito, o contador de saturação sobe e o monitor
 * permanece <em>due</em> para o próximo tick. Saturação <b>nunca</b> é
 * contada como erro interno nem como falha de alvo, e nunca derruba o
 * scheduler.</p>
 *
 * <p><b>Contadores</b> ({@link AtomicLong}): {@code dispatched} (aceitos),
 * {@code saturated} (recusados por fila cheia) e {@code internalErrors}
 * (exceções que escaparam do executor, resultados
 * {@code SENTINEL_INTERNAL_FAILURE} e falhas das etapas de persistência /
 * avaliação de estado do Prompt 10 — tudo do NOSSO lado). Falhas de alvo não
 * entram aqui.</p>
 *
 * <p><b>Dependências do pipeline (Prompt 10, estendido no 13):</b> este bean
 * injeta {@link MonitorCheckRecorder}, {@link OperationalStateEvaluator} e
 * {@link com.sentinel.alerting.AlertTrigger} apenas para repassá-los à
 * {@link MonitorExecutionTask} — quem executa o trabalho é a task, na própria
 * thread do pool, com fronteira de exceção por etapa.</p>
 */
@Component
public class MonitoringDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MonitoringDispatcher.class);

    /** Quanto o shutdown espera as checagens em voo terminarem. */
    private static final int SHUTDOWN_AWAIT_SECONDS = 5;

    /** Prefixo das threads do pool — identifica a fronteira em thread dumps. */
    private static final String THREAD_NAME_PREFIX = "sentinel-monitoring-";

    /** Resultado da tentativa de despacho — enum sem estado, seguro entre threads. */
    public enum DispatchOutcome {
        /** Task aceita: o monitor sai do ciclo "due" neste tick. */
        ACCEPTED,
        /** Monitor já em execução: nada foi submetido; segue due. */
        IN_FLIGHT,
        /** Fila/pool saturados: nada foi submetido; segue due. */
        SATURATED
    }

    private final ThreadPoolTaskExecutor taskExecutor;
    private final HttpMonitorExecutor monitorExecutor;
    private final MonitorCheckRecorder recorder;
    private final OperationalStateEvaluator evaluator;
    private final AlertTrigger alertTrigger;
    private final DueTracker dueTracker;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private final AtomicLong dispatchedCount = new AtomicLong();
    private final AtomicLong saturatedCount = new AtomicLong();
    private final AtomicLong internalErrorCount = new AtomicLong();

    public MonitoringDispatcher(
            MonitoringProperties properties,
            HttpMonitorExecutor monitorExecutor,
            MonitorCheckRecorder recorder,
            OperationalStateEvaluator evaluator,
            AlertTrigger alertTrigger,
            DueTracker dueTracker) {
        this.monitorExecutor = monitorExecutor;
        this.recorder = recorder;
        this.evaluator = evaluator;
        this.alertTrigger = alertTrigger;
        this.dueTracker = dueTracker;
        this.taskExecutor = createTaskExecutor(properties);
    }

    private static ThreadPoolTaskExecutor createTaskExecutor(MonitoringProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        // Config inválida (worker-count < 1, queue < 0) derruba o startup aqui:
        // fail-fast, sem fallback silencioso.
        executor.initialize();
        return executor;
    }

    /**
     * Tenta despachar a checagem de um monitor.
     *
     * <p>A guarda de in-flight e a submissão são atômicas em relação a outros
     * chamadores: a entrada em {@code inFlight} acontece ANTES do
     * {@code submit} e é desfeita em qualquer recusa.</p>
     *
     * @param monitor monitor vencido
     * @return o desfecho da tentativa — saturação não é exceção para o chamador
     */
    public DispatchOutcome tryDispatch(Monitor monitor) {
        UUID monitorId = monitor.getId();
        if (!inFlight.add(monitorId)) {
            // Já em execução — checado ANTES (e independente) de qualquer
            // commit de vencimento feito pelo scheduler.
            return DispatchOutcome.IN_FLIGHT;
        }
        try {
            taskExecutor.execute(new MonitorExecutionTask(
                    monitor, monitorExecutor, this, recorder, evaluator, alertTrigger, dueTracker));
            dispatchedCount.incrementAndGet();
            return DispatchOutcome.ACCEPTED;
        }
        catch (RejectedExecutionException ex) {
            inFlight.remove(monitorId);
            saturatedCount.incrementAndGet();
            log.debug("monitoring dispatch saturated monitorId={}", monitorId);
            return DispatchOutcome.SATURATED;
        }
    }

    /** Libera o monitor do conjunto de em-voo (chamado pela task em {@code finally}). */
    void release(UUID monitorId) {
        inFlight.remove(monitorId);
    }

    /** Resultado com desfecho interno — contado, nunca atribuído ao alvo. */
    void recordResult(MonitorExecutionResult result) {
        if (result.outcome() == MonitorOutcome.SENTINEL_INTERNAL_FAILURE) {
            internalErrorCount.incrementAndGet();
        }
    }

    /** Exceção que escapou do executor — bug nosso, contado como interno. */
    void recordInternalError(UUID monitorId, RuntimeException ex) {
        internalErrorCount.incrementAndGet();
        log.error("monitor task failed unexpectedly monitorId={}", monitorId, ex);
    }

    // --- observabilidade ----------------------------------------------------

    /** Aceitos desde o startup. */
    public long dispatchedCount() {
        return dispatchedCount.get();
    }

    /** Recusados por fila/pool saturados desde o startup. */
    public long saturatedCount() {
        return saturatedCount.get();
    }

    /** Erros internos (bug de código) desde o startup — nunca falha de alvo. */
    public long internalErrorCount() {
        return internalErrorCount.get();
    }

    /** Checagens em voo agora (0 fora dos ticks). */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * Espera (limitada) todas as checagens em voo terminarem — usada pelos
     * testes e disponível para desligamento gracioso.
     *
     * @param timeout limite máximo de espera
     * @return {@code true} se esvaziou dentro do limite
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!inFlight.isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Desligamento gracioso: espera até {@value SHUTDOWN_AWAIT_SECONDS}s
     * pelas checagens em voo (threads {@code sentinel-monitoring-*}).
     */
    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdown();
    }
}
