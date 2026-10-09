package com.sentinel.project.alert.automation;

import java.util.UUID;

/**
 * Hook de PÓS-AVALIAÇÃO (Prompt 15) — o único ponto em que o motor de
 * avaliação ({@code com.sentinel.alerting}) chama de volta este pacote, com
 * uma assinatura neutra: "terminei de avaliar o serviço X".
 *
 * <p><b>Direção da dependência:</b> o engine (cliente) conhece ESTA
 * interface; a implementação ({@link AlertAutomationReconciler}) mora aqui,
 * do lado do domínio de automação. O pacote do engine continua sem
 * importar nada de destino — só o tipo do callback, sem nomes de destino,
 * mantendo verde a trava
 * {@code MonitoringEngineBoundaryTest.alertingPackageHasNoIncidentReferences}.</p>
 *
 * <p><b>Quando roda:</b> ao fim do caminho normal de
 * {@code AlertEngine.evaluateService}, DEPOIS das transições do ciclo
 * (nasceu/toque/resolução) — nunca nos atalhos de serviço sumido ou
 * inativo, onde não houve avaliação nenhuma. É a "reconciliação": o evento
 * de nascimento resolve o caso comum (alerta novo); este hook cobre o que o
 * evento sozinho não alcança (flag ligada DEPOIS do disparo, evento perdido
 * por falha transitória).</p>
 *
 * <p><b>Contrato de robustez:</b> implementações NUNCA propagam exceção — o
 * engine chama dentro de try/catch e o ciclo de avaliação não pode cair
 * por causa da automação (mesma filosofia de isolamento POR REGRA do
 * próprio engine).</p>
 */
public interface AlertPostEvaluationHook {

    /**
     * Chamado ao fim de uma avaliação bem-sucedida de um serviço.
     *
     * <p><b>Contrato do callback:</b></p>
     * <ul>
     *   <li><b>Síncrono, no mesmo thread da avaliação.</b> Não existe
     *       agendamento próprio nem fila: o tempo que este método gastar
     *       atrasa o {@code evaluateService} que o chamou. Implementações
     *       devem ser previsíveis (consulta coberta por índice, escritas
     *       pontuais) e nunca esperar I/O externo à aplicação;</li>
     *   <li><b>Não propaga.</b> Qualquer exceção é capturada pelo engine e
     *       logada; o ciclo de avaliação não cai por causa da automação.
     *       Quem implementa captura POR ALERTA/por item para não perder o
     *       resto do lote;</li>
     *   <li><b>Não decide o veredito.</b> Roda DEPOIS das transições do
     *       ciclo (nasceu/toque/resolução) — só deriva efeito do que já foi
     *       decidido, nunca muda status, severidade ou resolução;</li>
     *   <li><b>Pode repetir.</b> Chamado a cada avaliação, inclusive quando
     *       nada mudou — a implementação precisa ser idempotente (rodar N
     *       vezes no mesmo estado = rodar uma).</li>
     * </ul>
     *
     * @param serviceId serviço que acabou de ser avaliado (chegou do engine,
     *                  já resolvido e com pais ativos)
     */
    void afterEvaluation(UUID serviceId);
}
