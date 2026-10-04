package com.sentinel.project.alert.automation;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.service.IncidentService;

import org.springframework.stereotype.Component;

/**
 * Correlação da automação (Prompt 15) — encontra o incidente automático
 * ABERTO do serviço para REUTILIZAR.
 *
 * <p><b>Uma linha de defesa, não a autoridade sozinha:</b> o pré-check
 * mora na leitura ({@code source = ALERT_AUTOMATION} e
 * {@code status <> RESOLVED}) e a garantia de "no máximo UM" é o índice
 * parcial {@code uq_incidents_auto_open_per_service} da V12 — sob corrida
 * de dois ciclos criando ao mesmo tempo, um insert perde e faz rollback
 * inteiro, nunca duplica.</p>
 *
 * <p><b>Escopo de serviço, sempre:</b> a consulta é por
 * {@code serviceId} — automação nunca enxerga incidente de outro serviço
 * (cross-service é proibido) e nunca "adota" um incidente que um HUMANO
 * abriu (o filtro de origem é isso: um incidente manual continua sendo da
 * história manual, mesmo do mesmo serviço).</p>
 */
@Component
public class AlertIncidentCorrelator {

    private final IncidentService incidentService;

    public AlertIncidentCorrelator(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /**
     * @param serviceId serviço dono do alerta (chegou escopado do evento/hook)
     * @return o incidente automático aberto do serviço, ou vazio quando não
     *         há nenhum (é o caso de CRIAR)
     */
    public Optional<Incident> findOpen(UUID serviceId) {
        return incidentService.findOpenAutomatedByServiceId(serviceId);
    }
}
