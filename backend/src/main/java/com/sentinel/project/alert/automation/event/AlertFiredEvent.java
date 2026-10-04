package com.sentinel.project.alert.automation.event;

import java.util.UUID;

/**
 * Evento neutro de NASCIMENTO de alerta (Prompt 15) — o único canal pelo qual
 * o motor de avaliação ({@code com.sentinel.alerting}) fica sabendo que um
 * alerta novo existe, sem jamais nomear o que vem depois.
 *
 * <p><b>Neutro de propósito:</b> o record mora em {@code alert.automation}
 * (fora do pacote do engine), carrega SÓ ids ({@code alertId} e
 * {@code serviceId}) e não menciona o destino da automação — o engine
 * publica "nasceu um alerta" e quem quiser reagir se inscreve daqui para
 * fora. É o que mantém a trava
 * {@code MonitoringEngineBoundaryTest.alertingPackageHasNoIncidentReferences}
 * verde: nenhum nome, tipo ou conceito de destino entra no pacote do engine.</p>
 *
 * <p><b>Por que id do serviço junto:</b> todo lookup da automação é escopado
 * ({@code findByServiceIdAndId}) — nunca {@code findById} avulso, mesmo
 * anti-IDOR do resto do projeto. O par (serviceId, alertId) prova a cadeia
 * do DONO antes de qualquer escrita.</p>
 *
 * <p><b>Publicado uma vez, no ramo de NASCIMENTO</b> do
 * {@code AlertLifecycleService.fire} (após {@code saveAndFlush} do alerta
 * novo). O toque de um alerta que já existia NÃO publica: "virou alerta"
 * aconteceu uma vez só.</p>
 *
 * @param alertId  alerta recém-criado (coluna {@code alerts.id})
 * @param serviceId serviço dono do alerta (escopo de todo lookup posterior)
 */
public record AlertFiredEvent(UUID alertId, UUID serviceId) {
}
