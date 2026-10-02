package com.sentinel.project.alert.model;

/**
 * Status de UM alerta (Prompt 13) — dois estados, máquina fechada:
 * {@link #FIRING} (condição valendo) → {@link #RESOLVED} (condição encerrada).
 *
 * <p><b>Nunca o contrário:</b> resolver não reaproveita a linha para disparar
 * de novo — uma regra que dispara, resolve e volta a estourar nasce UM NOVO
 * alerta (com {@code startedAt} próprio). É assim que a lista "mais recente
 * primeiro" continua sendo a história correta e que o índice parcial
 * {@code UNIQUE (alert_rule_id) WHERE status = 'FIRING'} da migration V10
 * expressa exatamente "no máximo um aberto por regra".</p>
 *
 * <p>Persistido como {@code VARCHAR(10)} + CHECK {@code alerts_status_check}.</p>
 */
public enum AlertStatus {

    /** Condição da regra valendo agora — existe no máximo UM por regra. */
    FIRING,

    /** Condição encerrada — motivo em {@code AlertResolutionReason}. */
    RESOLVED
}
