package com.sentinel.alerting;

import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link AlertTrigger} de produção (Prompt 13) — traduz o monitor que acabou
 * de rodar no serviço dono e delega o ciclo ao {@link AlertEngine}.
 *
 * <p><b>Isolamento absoluto (correção de revisão):</b> TODO o corpo está em
 * try/catch. Falha de banco, de métrica ou de transação vira log com
 * serviceId/ruleId (nunca payload, URL ou corpo de check) e o método devolve
 * normalmente — o histórico já persistido, o estado do serviço e a liberação
 * do in-flight na task seguem intactos. A task ainda tem o próprio
 * try/catch: este bean não é a única barreira.</p>
 */
@Component
public class MonitoringAlertTrigger implements AlertTrigger {

    private static final Logger log = LoggerFactory.getLogger(MonitoringAlertTrigger.class);

    private final AlertEngine alertEngine;

    public MonitoringAlertTrigger(AlertEngine alertEngine) {
        this.alertEngine = alertEngine;
    }

    @Override
    public void trigger(Monitor monitor) {
        try {
            UUID serviceId = monitor.getService() == null ? null : monitor.getService().getId();
            if (serviceId == null) {
                log.debug("alert evaluation skipped: monitor without service monitorId={}",
                        monitor.getId());
                return;
            }
            alertEngine.evaluateService(serviceId);
        }
        catch (RuntimeException ex) {
            // Nunca propaga: o motor de monitoramento não pode morrer aqui.
            log.error("alert engine failed monitorId={}", monitor.getId(), ex);
        }
    }
}
