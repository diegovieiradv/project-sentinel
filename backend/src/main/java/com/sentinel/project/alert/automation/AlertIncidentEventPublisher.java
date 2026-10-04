package com.sentinel.project.alert.automation;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.repository.IncidentEventRepository;

import org.springframework.stereotype.Component;

/**
 * Timeline da automação (Prompt 15) — grava as linhas que explicam o
 * nascimento/associação de um incidente automático, com ATOR NULO.
 *
 * <p><b>Sem transação própria:</b> este componente é chamado dentro da
 * transação {@code REQUIRES_NEW} do {@link AlertIncidentAutomation} e nela
 * participa ({@code REQUIRED} implícito) — a regra "mudança E linha de
 * timeline na MESMA transação" vale aqui exatamente como no
 * {@code IncidentService}: não existe estado em que o incidente nasceu
 * sem o evento, nem evento sem o incidente.</p>
 *
 * <p><b>Ator nulo é informação, não lacuna:</b> {@code actorUserId}
 * {@code null} significa "isto não foi uma pessoa" — mesma leitura do
 * {@code createdByUserId} nulo da coluna. Nenhum usuário é inventado,
 * nenhum "sistema" é forjado como se fosse conta.</p>
 *
 * <p><b>Mensagens iguais às do caminho manual</b> ({@code CREATED} =
 * {@code "Incident created"}, {@code ALERT_LINKED} =
 * {@code "Alert {id} linked"}): a diferença entre automação e humano no
 * histórico é o {@code source} do incidente e o ator nulo das linhas, não
 * frases paralelas que a UI teria que aprender duas vezes.</p>
 */
@Component
public class AlertIncidentEventPublisher {

    /**
     * Folha mínima entre dois eventos da MESMA operação — duplicada de
     * {@code IncidentService.nextEventStamp} (método privado daquela
     * classe): expor um utilitário de carimbo só por estas ~6 linhas
     * abriria API pública onde não precisa, e a ordenação
     * {@code createdAt ASC, id ASC} depende da folha, não da fonte.
     * TIMESTAMPTZ guarda µs; {@code Instant.now()} duas vezes seguidas
     * cairia no mesmo tick.
     */
    private static final long EVENT_STAMP_GAP_MILLIS = 1;

    private final IncidentEventRepository incidentEventRepository;

    public AlertIncidentEventPublisher(IncidentEventRepository incidentEventRepository) {
        this.incidentEventRepository = incidentEventRepository;
    }

    /**
     * Grava as linhas da operação, na ordem, com carimbo estritamente
     * crescente.
     *
     * @param incident incidente criado ou reutilizado
     * @param alertId  alerta envolvido (aparece na mensagem do link)
     * @param created  gravar a linha {@code CREATED} (nascceu agora)
     * @param linked   gravar a linha {@code ALERT_LINKED} (o UPDATE venceu)
     */
    public void publish(Incident incident, UUID alertId, boolean created, boolean linked) {
        if (!created && !linked) {
            return; // nada mudou na timeline (corrida benigna já decidida)
        }
        Instant stamp = null;
        if (created) {
            stamp = nextEventStamp(stamp);
            incidentEventRepository.save(new IncidentEvent(
                    incident.getId(), IncidentEventType.CREATED, "Incident created", null, stamp));
        }
        if (linked) {
            stamp = nextEventStamp(stamp);
            incidentEventRepository.save(new IncidentEvent(
                    incident.getId(),
                    IncidentEventType.ALERT_LINKED,
                    "Alert " + alertId + " linked",
                    null,
                    stamp));
        }
    }

    /** Próximo carimbo da operação: relógio real com piso de 1&nbsp;ms. */
    private static Instant nextEventStamp(Instant previous) {
        Instant now = Instant.now();
        if (previous == null) {
            return now;
        }
        Instant floor = previous.plusMillis(EVENT_STAMP_GAP_MILLIS);
        return now.isAfter(floor) ? now : floor;
    }
}
