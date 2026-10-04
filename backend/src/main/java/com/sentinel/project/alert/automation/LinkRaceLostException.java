package com.sentinel.project.alert.automation;

import java.util.UUID;

/**
 * Corrida de vínculo perdida (Prompt 15, revisão P1-1) — lançada quando a
 * automação CACOU um incidente mas o {@code UPDATE} condicional do alerta
 * devolveu 0 linhas: um vínculo humano (ou de outra automação) venceu a
 * corrida entre a rechecagem de elegibilidade e a escrita.
 *
 * <p><b>Por que exceção, e não "deixa o incidente aberto":</b> neste ponto a
 * criação já está escrita DENTRO da transação da automação e o alerta que a
 * motivou não vai apontar para ela — a linha ficaria órfã (nasceu sem nenhum
 * alerta associado, só a própria linha {@code CREATED} da timeline) e
 * contraria o contrato "alerta já vinculado é no-op, sem criar nada".
 * Propagar daqui dá {@code ROLLBACK} da criação <b>e</b> de qualquer evento
 * desta transação numa única operação atômica; apagar a linha depois seria a
 * mesma escrita em dois passos, com mais um ponto de falha entre eles (e a
 * timeline teria que ser desfeita à mão, com a FK dos eventos).</p>
 *
 * <p><b>Quem absorve:</b> o {@code AlertIncidentAutomationListener} e o
 * {@code AlertAutomationReconciler} capturam {@code RuntimeException} e
 * logam — a automação nunca derruba o ciclo de avaliação nem o nascimento do
 * alerta. É uma corrida benigna e rara: o alerta continua FIRING, já
 * vinculado ao incidente certo pelo humano, e a reconciliação do ciclo
 * seguinte é no-op (rechecagem 3).</p>
 */
public class LinkRaceLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID serviceId;
    private final UUID alertId;
    private final UUID rolledBackIncidentId;

    public LinkRaceLostException(UUID serviceId, UUID alertId, UUID rolledBackIncidentId) {
        super("automation link raced serviceId=" + serviceId
                + " alertId=" + alertId
                + " rolledBackIncidentId=" + rolledBackIncidentId);
        this.serviceId = serviceId;
        this.alertId = alertId;
        this.rolledBackIncidentId = rolledBackIncidentId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public UUID getAlertId() {
        return alertId;
    }

    /** Id do incidente criado nesta tentativa — que o rollback apagou. */
    public UUID getRolledBackIncidentId() {
        return rolledBackIncidentId;
    }
}
