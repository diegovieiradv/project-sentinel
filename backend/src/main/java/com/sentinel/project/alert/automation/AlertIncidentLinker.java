package com.sentinel.project.alert.automation;

import java.util.UUID;

import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.incident.model.Incident;

import org.springframework.stereotype.Component;

/**
 * Vínculo Alert ↔ Incident da automação (Prompt 15) — o UPDATE condicional
 * que o Prompt 14 construiu, reusado aqui SEM reescrever o alerta.
 *
 * <p><b>Mesma barreira de corrida do caminho manual</b>
 * ({@code IncidentService.linkAlert}): {@code UPDATE ... WHERE incident IS
 * NULL} devolve 1 quando ESTA chamada fez o vínculo e 0 quando ele já
 * existia (próprio ou de outra request/automação que venceu). Aqui, ao
 * contrário da API, 0 linhas não é conflito a reportar — é corrida
 * benigna e o orquestrador decide o que fazer: <b>reutilizando</b> um
 * incidente, 0 linhas é no-op sem evento novo (a reconciliação do ciclo
 * seguinte reavalia); <b>recém-criado</b>, 0 linhas significa que a criação
 * ficaria órfã e o orquestrador lança {@code LinkRaceLostException} para
 * reverter a transação inteira (revisão P1-1).</p>
 *
 * <p><b>Nenhum outro campo do alerta é tocado:</b> a escrita é SQL puro
 * na coluna {@code incident_id} — mensagem, severidade, status e
 * timestamps do alerta ficam byte-idênticos (mesmo contrato de
 * imutabilidade do Prompt 14).</p>
 */
@Component
public class AlertIncidentLinker {

    private final AlertRepository alertRepository;

    public AlertIncidentLinker(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /**
     * @return {@code true} quando ESTA chamada fez o vínculo; {@code false}
     *         quando ele já existia (outra parte venceu a corrida)
     */
    public boolean link(UUID alertId, Incident incident) {
        return alertRepository.linkIncident(alertId, incident) == 1;
    }
}
