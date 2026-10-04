package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.repository.IncidentEventRepository;
import com.sentinel.project.incident.repository.IncidentRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base dos testes do Prompt 14 (Incidents) — reaproveita o vocabulário do
 * {@link AbstractAlertEngineIntegrationTest} (fixtures de tenant, URLs de
 * projeto/serviço, criação de monitor e regra) para que os testes de vínculo
 * consigam disparar um alerta real, e acrescenta o vocabulário de incidente:
 * URL de rota, criação via API, mudança de status e leitura direta do
 * repositório.
 *
 * <p>Como as demais bases, esta classe <b>não</b> tem anotações Spring nem
 * ciclo de vida: cada subclasse declara o seu próprio
 * {@code @SpringBootTest}/{@code @ServiceConnection}/container (uma classe de
 * contexto por container). As subclasses que NÃO importam
 * {@link FixedMetricsClockConfig} rodam com o {@code SystemClock} real — é o
 * caso dos testes de {@code startedAt}, que precisam do relógio corrente para
 * exercitar as janelas de validação.</p>
 *
 * <p><b>Convenção dos payloads:</b> DTOs não têm campos de escopo, então o
 * mapa do POST é sempre o mesmo — os testes só sobrepõem o campo que estão
 * exercitando.</p>
 */
public abstract class AbstractIncidentIntegrationTest extends AbstractAlertEngineIntegrationTest {

    @Autowired
    protected IncidentRepository incidentRepository;

    @Autowired
    protected IncidentEventRepository incidentEventRepository;

    // --- URLs ---------------------------------------------------------------

    protected String incidentsUrl(String organizationId, String projectId, String serviceId) {
        return serviceUrl(organizationId, projectId, serviceId) + "/incidents";
    }

    protected String incidentUrl(
            String organizationId, String projectId, String serviceId, String incidentId) {
        return incidentsUrl(organizationId, projectId, serviceId) + "/" + incidentId;
    }

    protected String incidentStatusUrl(
            String organizationId, String projectId, String serviceId, String incidentId) {
        return incidentUrl(organizationId, projectId, serviceId, incidentId) + "/status";
    }

    protected String incidentEventsUrl(
            String organizationId, String projectId, String serviceId, String incidentId) {
        return incidentUrl(organizationId, projectId, serviceId, incidentId) + "/events";
    }

    protected String incidentAlertsUrl(
            String organizationId, String projectId, String serviceId, String incidentId) {
        return incidentUrl(organizationId, projectId, serviceId, incidentId) + "/alerts";
    }

    protected String incidentAlertUrl(
            String organizationId,
            String projectId,
            String serviceId,
            String incidentId,
            String alertId) {
        return incidentAlertsUrl(organizationId, projectId, serviceId, incidentId) + "/" + alertId;
    }

    // --- payloads -----------------------------------------------------------

    /** Payload mínimo VÁLIDO (título + severidade, sem campos de escopo). */
    protected Map<String, Object> incidentPayload(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("severity", "SEV3");
        return body;
    }

    /** Payload completo: título, descrição e severidade explícita. */
    protected Map<String, Object> incidentPayload(
            String title, String description, AlertSeverity severity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        if (description != null) {
            body.put("description", description);
        }
        body.put("severity", severity.name());
        return body;
    }

    // --- criação via API ----------------------------------------------------

    /** POST → 201, devolve o id, com o payload montado pelo teste. */
    protected String createIncident(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            Map<String, Object> body)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        postCsrf(incidentsUrl(organizationId, projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the incident id").isNotBlank();
        return id;
    }

    /** POST canônico (título + SEV3) → 201. */
    protected String createIncident(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String title)
            throws Exception {
        return createIncident(
                bearer, organizationId, projectId, serviceId, incidentPayload(title));
    }

    // --- ciclo --------------------------------------------------------------

    /** PATCH .../status → 200 (controla o estado nos testes de imutabilidade). */
    protected void changeIncidentStatus(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String incidentId,
            String target)
            throws Exception {
        mockMvc.perform(patchCsrf(
                                incidentStatusUrl(
                                        organizationId, projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", target))))
                .andExpect(status().isOk());
    }

    // --- leitura persistida -------------------------------------------------

    /** Incidente via repositório (estado final depois de um 400/409/corrida). */
    protected Incident incidentOf(
            String serviceId, String incidentId) {
        return incidentRepository
                .findByServiceIdAndId(
                        UUID.fromString(serviceId), UUID.fromString(incidentId))
                .orElseThrow();
    }

    /** Incidentes do serviço na ordem da API (startedAt DESC, id DESC). */
    protected List<Incident> incidentsOf(String serviceId) {
        return incidentRepository
                .search(UUID.fromString(serviceId), null, null, PageRequest.of(0, 100,
                        Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))))
                .getContent();
    }

    /** Timeline persistida de UM incidente na ordem de emissão. */
    protected List<IncidentEvent> eventsOf(String incidentId) {
        return incidentEventRepository.findByIncidentId(UUID.fromString(incidentId));
    }

    /** Tipos da timeline, na ordem — atalho das asserções de sequência. */
    protected List<IncidentEventType> eventTypes(String incidentId) {
        List<IncidentEventType> types = new ArrayList<>();
        for (IncidentEvent event : eventsOf(incidentId)) {
            types.add(event.getType());
        }
        return types;
    }
}
