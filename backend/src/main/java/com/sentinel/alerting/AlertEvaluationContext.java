package com.sentinel.alerting;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.model.ServiceStatus;

/**
 * Instante-e-estado com que UMA regra é avaliada num ciclo (Prompt 13) — o
 * mínimo que os cinco tipos de regra compartilham, sem levar entidade nenhuma
 * dentro do avaliador puro.
 *
 * <p><b>Por que um record:</b> o avaliador não escreve nada; ele só lê. Um
 * record imutável torna isso óbvio na assinatura e permite montar o contexto
 * num teste com uma linha, sem Spring nem banco.</p>
 *
 * @param serviceId       serviço dono da regra (só para logs/diagnóstico)
 * @param serviceStatus   estado operacional ATUAL do serviço (escrito pelo
 *                        {@code OperationalStateEvaluator}, Prompt 10)
 * @param statusChangedAt QUANDO o estado atual começou a valer ({@code null}
 *                        = nenhuma transição registrada — a regra
 *                        SERVICE_STATUS trata como NO_DATA)
 * @param now             instante do ciclo, do relógio injetado
 */
public record AlertEvaluationContext(
        UUID serviceId,
        ServiceStatus serviceStatus,
        Instant statusChangedAt,
        Instant now) {
}
