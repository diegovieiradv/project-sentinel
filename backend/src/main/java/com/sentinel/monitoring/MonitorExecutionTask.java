package com.sentinel.monitoring;

import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unidade de trabalho do pool de checagens (Prompt 09).
 *
 * <p><b>Isolamento de exceção:</b> esta Runnable é a fronteira da thread do
 * pool. Qualquer falha — inclusive um {@link HttpMonitorExecutor} que viole o
 * contrato de "nunca lança" — é capturada aqui, contada como erro interno e
 * registrada em log; a exceção <b>nunca</b> chega ao executor do pool (o que
 * mataria silenciosamente a tarefa e deixaria o monitor "preso" em voo).</p>
 *
 * <p><b>Liberação em {@code finally}:</b> o in-flight do
 * {@link MonitoringDispatcher} é removido SEMPRE (sucesso, timeout,
 * exceção), de modo que o monitor volta a poder ser despachado no próximo
 * vencimento. Liberar só no caminho de sucesso deixaria o monitor bloqueado
 * para sempre após a primeira falha.</p>
 *
 * <p><b>Log sem segredo:</b> monitorId, serviceId, desfecho, status HTTP e
 * duração. Nunca corpo, URL com query string, Authorization ou cookie.</p>
 */
final class MonitorExecutionTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MonitorExecutionTask.class);

    private final Monitor monitor;
    private final HttpMonitorExecutor executor;
    private final MonitoringDispatcher dispatcher;

    MonitorExecutionTask(Monitor monitor, HttpMonitorExecutor executor, MonitoringDispatcher dispatcher) {
        this.monitor = monitor;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {
        try {
            MonitorExecutionResult result = executor.execute(monitor);
            logResult(result);
            dispatcher.recordResult(result);
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
