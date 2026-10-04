package com.sentinel.project.alert.automation;

import com.sentinel.project.alert.automation.event.AlertFiredEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidor do nascimento de alerta (Prompt 15) — liga o
 * {@code AlertFiredEvent} (publicado pelo engine) à automação, DEPOIS do
 * commit do alerta.
 *
 * <p><b>{@code AFTER_COMMIT} de propósito:</b> reagir durante a transação
 * do nascimento amarraria o destino da automação ao do alerta (falha aqui
 * reverteria o alerta, ou pior: alerta comitado e destino pendurado).
 * Rodar após o commit garante "o alerta EXISTE; agora sim, considere o
 * incidente" — e a automação tem a SUA transação
 * ({@code REQUIRES_NEW} aberto pelo orquestrador), independente.</p>
 *
 * <p><b>try/catch que NÃO propaga:</b> qualquer falha é logada e absorvida
 * — o alerta continua disparado, sem incidente, e a reconciliação do
 * próximo ciclo ({@code AlertAutomationReconciler}) fecha o caso. Uma
 * exceção aqui viraria erro no callback de transação do Spring sem ganhar
 * benefício algum (o commit já aconteceu) — é o caso também da
 * {@code LinkRaceLostException} da corrida de vínculo, cujo rollback da
 * criação JÁ aconteceu antes de chegar aqui.</p>
 *
 * <p><b>Sem escrita própria, sem transação própria:</b> este listener só
 * encaminha — a atomicidade (correlação + criação + vínculo + timeline)
 * é do {@link AlertIncidentAutomation#automate}.</p>
 */
@Component
public class AlertIncidentAutomationListener {

    private static final Logger log = LoggerFactory.getLogger(AlertIncidentAutomationListener.class);

    private final AlertIncidentAutomation automation;

    public AlertIncidentAutomationListener(AlertIncidentAutomation automation) {
        this.automation = automation;
    }

    /**
     * Alerta nasceu → considere automatizar. O toque de um alerta
     * existente não publica evento, então este caminho só acontece UMA vez
     * por alerta (a reconciliação cobre as demais situações).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertFired(AlertFiredEvent event) {
        try {
            automation.automate(event.serviceId(), event.alertId());
        }
        catch (RuntimeException ex) {
            log.error("alert automation failed alertId={} serviceId={}",
                    event.alertId(), event.serviceId(), ex);
        }
    }
}
