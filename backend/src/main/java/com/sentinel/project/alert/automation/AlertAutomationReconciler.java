package com.sentinel.project.alert.automation;

import java.util.List;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciliação da automação (Prompt 15) — implementação do
 * {@link AlertPostEvaluationHook}: ao fim de CADA avaliação de serviço,
 * "relembra" os alertas ainda elegíveis que ficaram por conta do evento.
 *
 * <p><b>Por que um hook e um evento coexistem:</b> o
 * {@code AlertFiredEvent} resolve o caso comum (alerta nasceu agora →
 * automatize na hora), mas sozinho deixa buracos: regra ligada a
 * {@code autoCreateIncident} DEPOIS de o alerta já estar disparado; evento
 * perdido porque a automação falhou transitória; alerta criado enquanto a
 * flag estava desligada. Este hook fecha esses casos reavaliando o estado
 * REAL a cada ciclo — sem scheduler, sem polling por tempo: o gatilho é a
 * própria avaliação que já ia acontecer.</p>
 *
 * <p><b>Isolamento POR ALERTA:</b> a query devolve IDs (a entidade não é
 * materializada aqui) e cada alerta é processado numa transação PRÓPRIA
 * ({@link AlertIncidentAutomation#automate} abre a sua com
 * {@code REQUIRES_NEW}), com
 * try/catch individual — um alerta que falha é logado e os demais seguem,
 * mesma filosofia de isolamento por regra do engine. Este bean NÃO tem
 * {@code @Transactional} de propósito: quem decide a fronteira de
 * transação é a automação, um alerta por vez.</p>
 *
 * <p><b>Idempotente por construção:</b> rodar N vezes no mesmo estado é
 * equivalente a rodar uma — a rechecagem de elegibilidade dentro da
 * transação da automação descarta o que já foi vinculado ou não está mais
 * disparando.</p>
 */
@Component
public class AlertAutomationReconciler implements AlertPostEvaluationHook {

    private static final Logger log = LoggerFactory.getLogger(AlertAutomationReconciler.class);

    private final AlertRepository alertRepository;
    private final AlertIncidentAutomation automation;

    public AlertAutomationReconciler(AlertRepository alertRepository, AlertIncidentAutomation automation) {
        this.alertRepository = alertRepository;
        this.automation = automation;
    }

    /**
     * Varre os alertas elegíveis do serviço e automatiza UM a UM.
     *
     * <p>Consulta barata coberta por {@code idx_alerts_auto_create_firing}
     * (V12) e naturalmente pequena: o engine garante no máximo UM alerta
     * aberto por regra, então o conjunto tem no máximo quantas regras
     * opt-in o serviço tem — na prática 0 ou 1.</p>
     */
    @Override
    public void afterEvaluation(UUID serviceId) {
        List<UUID> eligible =
                alertRepository.findOpenEligibleForAutomation(serviceId, AlertStatus.FIRING);
        for (UUID alertId : eligible) {
            try {
                automation.automate(serviceId, alertId);
            }
            catch (RuntimeException ex) {
                // isolamento por alerta: um falhou, o ciclo seguinte decide
                log.error("alert automation reconciliation failed serviceId={} alertId={}",
                        serviceId, alertId, ex);
            }
        }
    }
}
