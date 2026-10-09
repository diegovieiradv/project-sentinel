package com.sentinel.monitoring;

import java.util.UUID;

import com.sentinel.alerting.AlertTrigger;
import com.sentinel.project.monitor.model.Monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unidade de trabalho do pool de checagens (Prompt 09, estendido no Prompt 10).
 *
 * <p><b>Isolamento de exceção:</b> esta Runnable é a fronteira da thread do
 * pool. Qualquer falha — inclusive um {@link HttpMonitorExecutor} que viole o
 * contrato de "nunca lança" — é capturada aqui, contada como erro interno e
 * registrada em log; a exceção <b>nunca</b> chega ao executor do pool (o que
 * mataria silenciosamente a tarefa e deixaria o monitor "preso" em voo).</p>
 *
 * <p><b>Pipeline do Prompt 10, em DUAS etapas com fronteira própria</b>
 * (decisão do orquestrador — sem {@code @Async}, sem método transacional
 * grande, tudo dentro desta thread que JÁ roda no pool):</p>
 * <ol>
 *   <li>{@link MonitorCheckRecorder#record} — grava o histórico e FAZ COMMIT
 *       (bean {@code @Transactional} próprio);</li>
 *   <li>{@link OperationalStateEvaluator#evaluate} — só roda se (1) deu
 *       certo, em transação separada.</li>
 * </ol>
 *
 * <p>Cada etapa tem seu {@code try/catch}: se a persistência falhar (banco
 * fora, FK estourada), a etapa (2) é <b>PULADA</b> — nunca se calcula
 * estado a partir de histórico que não foi gravado — e a task CONTINUA (o
 * in-flight é liberado no {@code finally}, o monitor volta a ser despachado
 * no próximo vencimento). Se só a avaliação falhar, o histórico segue
 * de pé (durabilidade independente) e o estado é recalculado no próximo
 * check.</p>
 *
 * <p><b>Cancelamento de execução em voo (Prompt 17):</b> um monitor removido
 * ou desativado no MEIO da checagem não deve ter o resultado persistido nem
 * avaliado. O sinal é o próprio {@link DueTracker}: a entrada do monitor
 * some quando o tick chama {@link DueTracker#retainOnly} e o tira do
 * conjunto de elegíveis. O gate roda UMA vez — DEPOIS da rede e ANTES de
 * logar, persistir e avaliar — e só cancela quando a entrada JÁ existia no
 * momento em que a task começou ({@code trackedAtStart}) e sumiu durante a
 * checagem: "sumiu" é exatamente a perda de elegibilidade. Um monitor
 * recém-criado ainda não tem horário gravado (o {@code commit} do próprio
 * tick só acontece depois que o dispatcher ACEITA), então ausência de
 * entrada é o estado NORMAL dele — não é sinal de cancelamento. Por isso
 * NÃO existe pré-check antes da rede: entre o submit e o commit existe uma
 * janela em que a entrada ainda não foi gravada, e um gate ali faria a task
 * desistir de checagens perfeitamente válidas.</p>
 *
 * <p><b>Log sem segredo:</b> monitorId, serviceId, desfecho, status HTTP e
 * duração. Nunca corpo, URL com query string, Authorization ou cookie — as
 * exceções registradas aqui são as NOSSAS (infraestrutura), não texto vindo
 * da rede.</p>
 */
final class MonitorExecutionTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MonitorExecutionTask.class);

    private final Monitor monitor;
    private final HttpMonitorExecutor executor;
    private final MonitoringDispatcher dispatcher;
    private final MonitorCheckRecorder recorder;
    private final OperationalStateEvaluator evaluator;
    private final AlertTrigger alertTrigger;
    private final DueTracker dueTracker;

    MonitorExecutionTask(
            Monitor monitor,
            HttpMonitorExecutor executor,
            MonitoringDispatcher dispatcher,
            MonitorCheckRecorder recorder,
            OperationalStateEvaluator evaluator,
            AlertTrigger alertTrigger,
            DueTracker dueTracker) {
        this.monitor = monitor;
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.recorder = recorder;
        this.evaluator = evaluator;
        this.alertTrigger = alertTrigger;
        this.dueTracker = dueTracker;
    }

    @Override
    public void run() {
        // Estado de elegibilidade NO MOMENTO em que a task começa: é a
        // referência do gate de cancelamento lá embaixo (ver Javadoc).
        boolean trackedAtStart = dueTracker.isTracked(monitor.getId());
        try {
            MonitorExecutionResult result = executor.execute(monitor);

            if (trackedAtStart && !dueTracker.isTracked(monitor.getId())) {
                // O monitor saiu do conjunto de elegíveis DURANTE a checagem:
                // o histórico não é gravado, o estado não é avaliado e nenhum
                // alerta nasce de uma checagem de um monitor desligado/removido.
                log.debug("monitor execution cancelled while in flight monitorId={}", monitor.getId());
                return;
            }

            logResult(result);
            dispatcher.recordResult(result);
            persistAndEvaluate(result);
        }
        catch (RuntimeException ex) {
            // Executor violou o contrato (bug) — contamos e seguimos.
            dispatcher.recordInternalError(monitor.getId(), ex);
        }
        finally {
            // Sempre: sucesso, timeout, exceção — o monitor sai do in-flight.
            dispatcher.release(monitor.getId());
        }
    }

    /**
     * (1) persiste o resultado; (2) se persistiu, avalia o estado do
     * serviço. Falha em (1) pula (2); falha em (2) não desfaz (1).
     */
    private void persistAndEvaluate(MonitorExecutionResult result) {
        try {
            recorder.record(result);
        }
        catch (RuntimeException ex) {
            dispatcher.recordInternalError(monitor.getId(), ex);
            return; // sem histórico não há o que avaliar
        }
        try {
            evaluator.evaluate(monitor.getId());
        }
        catch (RuntimeException ex) {
            // histórico durável: o estado fica velho até o próximo check
            dispatcher.recordInternalError(monitor.getId(), ex);
        }
        try {
            // etapa (3): avaliação das regras de alerta do serviço — o próprio
            // gatilho nunca lança; este catch é a barreira da fronteira.
            alertTrigger.trigger(monitor);
        }
        catch (RuntimeException ex) {
            dispatcher.recordInternalError(monitor.getId(), ex);
        }
    }

    private void logResult(MonitorExecutionResult result) {
        if (result.isSuccess()) {
            log.info(
                    "monitor check monitorId={} serviceId={} outcome={} httpStatus={} durationMs={}",
                    result.monitorId(),
                    serviceId(),
                    result.outcome(),
                    result.httpStatus().orElse(null),
                    result.durationMillis());
        }
        else {
            log.warn(
                    "monitor check monitorId={} serviceId={} outcome={} failureCategory={} "
                            + "httpStatus={} durationMs={} message={}",
                    result.monitorId(),
                    serviceId(),
                    result.outcome(),
                    result.failureCategory().orElse(null),
                    result.httpStatus().orElse(null),
                    result.durationMillis(),
                    result.message());
        }
    }

    private UUID serviceId() {
        return monitor.getService() == null ? null : monitor.getService().getId();
    }
}
