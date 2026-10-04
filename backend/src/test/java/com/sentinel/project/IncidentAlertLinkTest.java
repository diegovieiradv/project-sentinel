package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.sentinel.project.monitor.model.CheckOutcome;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Associação Alert ↔ Incident (Prompt 14) — cardinalidade, idempotência e
 * fronteira de mutabilidade contra contexto real.
 *
 * <ul>
 *   <li>{@code PUT} responde <b>200 IncidentResponse</b> e cria UM
 *       {@code ALERT_LINKED}.</li>
 *   <li>Repetir o PUT é <b>idempotente</b>: 200, vínculo inalterado e NENHUM
 *       evento novo.</li>
 *   <li>Alerta de OUTRO incidente → {@code 409 ALERT_ALREADY_LINKED}; alerta
 *       de OUTRO serviço → 404 genérico.</li>
 *   <li><b>N:1</b>: DOIS alertas distintos apontam para o MESMO incidente →
 *       {@code 200 + 200}, os dois na coleção e DOIS {@code ALERT_LINKED} na
 *       timeline (o contrato é 1 alerta ≤ 1 incidente × 0..N alertas por
 *       incidente — nunca um índice único em {@code alerts.incident_id}).</li>
 *   <li>{@code DELETE} responde <b>204</b>; repetir (ou desvincular o que já
 *       está solto) também 204 — e a segunda vez não emite evento.</li>
 *   <li>Depois de RESOLVED, link e unlink param a existir: ambos
 *       {@code 409 INCIDENT_RESOLVED}.</li>
 * </ul>
 *
 * <p><b>Relógio:</b> importa o {@link FixedMetricsClockConfig} porque o engine
 * de alertas só dispara dentro da janela congelada.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class IncidentAlertLinkTest extends AbstractIncidentIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    // --- cenário -------------------------------------------------------------

    private record Linked(Scene scene, String incidentId, String alertId) {
    }

    private Linked linkedScene(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org Incident " + prefix);
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor Link");
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra Link");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);
        evaluate(scene.serviceId());
        String alertId = singleAlertOf(scene.serviceId()).getId().toString();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente do link");
        return new Linked(scene, incidentId, alertId);
    }

    private String url(Linked linked, String incidentId) {
        return incidentAlertUrl(
                linked.scene().org().organizationId(), linked.scene().projectId(),
                linked.scene().serviceId(), incidentId, linked.alertId());
    }

    // --- PUT: 200 + evento ---------------------------------------------------

    @Test
    void putLinksAndEmitsExactlyOneAlertLinkedEvent() throws Exception {
        Linked linked = linkedScene("incident-link-put");
        String bearer = linked.scene().org().ownerBearer();

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(linked.incidentId()));

        // o alerta aponta para o incidente…
        assertThat(alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow()
                        .getIncident().getId())
                .isEqualTo(UUID.fromString(linked.incidentId()));

        // …e aparece na coleção do incidente
        mockMvc.perform(get(incidentAlertsUrl(
                                linked.scene().org().organizationId(),
                                linked.scene().projectId(),
                                linked.scene().serviceId(), linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(linked.alertId()));

        assertThat(eventTypes(linked.incidentId()))
                .containsExactly(
                        com.sentinel.project.incident.model.IncidentEventType.CREATED,
                        com.sentinel.project.incident.model.IncidentEventType.ALERT_LINKED);
    }

    @Test
    void linkDoesNotAlterAnyOtherAlertField() throws Exception {
        Linked linked = linkedScene("incident-link-fields");
        String bearer = linked.scene().org().ownerBearer();

        var before = alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow();
        String message = before.getMessage();
        var severity = before.getSeverity();
        var alertStatus = before.getStatus();
        var startedAt = before.getStartedAt();
        var resolvedAt = before.getResolvedAt();

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        var after = alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow();
        assertThat(after.getMessage()).isEqualTo(message);
        assertThat(after.getSeverity()).isEqualTo(severity);
        assertThat(after.getStatus()).isEqualTo(alertStatus);
        assertThat(after.getStartedAt()).isEqualTo(startedAt);
        assertThat(after.getResolvedAt()).isEqualTo(resolvedAt);
    }

    // --- PUT idempotente -----------------------------------------------------

    @Test
    void repeatingPutIsIdempotentAndEmitsNoNewEvent() throws Exception {
        Linked linked = linkedScene("incident-link-idem");
        String bearer = linked.scene().org().ownerBearer();

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        int eventsAfterFirst = eventsOf(linked.incidentId()).size();

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(linked.incidentId()));

        assertThat(eventsOf(linked.incidentId()))
                .as("repetir o PUT não re-emite ALERT_LINKED")
                .hasSize(eventsAfterFirst);

        assertThat(alertRepository.searchByIncidentId(
                        UUID.fromString(linked.incidentId()),
                        org.springframework.data.domain.PageRequest.of(0, 100))
                .getTotalElements())
                .isEqualTo(1);
    }

    // --- N:1: DOIS alertas no MESMO incidente -------------------------------

    /**
     * Contrato N:1 — dois alertas DISTINTOS apontando para o MESMO incidente:
     * {@code 200 + 200}, os dois na coleção e DOIS {@code ALERT_LINKED} na
     * timeline. É a prova do caminho que um índice ÚNICO em
     * {@code alerts.incident_id} proibia (viraria 500 no segundo PUT): a
     * cardinalidade correta é <b>1 alerta ≤ 1 incidente</b> ×
     * <b>0..N alertas por incidente</b>.
     */
    @Test
    void twoDistinctAlertsLinkToTheSameIncidentWithTwoEvents() throws Exception {
        Scene scene = newScene("incident-link-n1", "Org Incident N1");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor N1");
        // duas regras → dois alertas abertos independentes (dedup é POR regra)
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra N1 A");
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra N1 B");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);
        evaluate(scene.serviceId());

        var alerts = alertsOf(scene.serviceId());
        assertThat(alerts).as("dois alertas distintos no mesmo serviço").hasSize(2);
        String alertA = alerts.get(0).getId().toString();
        String alertB = alerts.get(1).getId().toString();

        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente N:1");

        mockMvc.perform(putCsrf(incidentAlertUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId, alertA))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId));

        // o segundo PUT no MESMO incidente é legítimo — nunca 500
        mockMvc.perform(putCsrf(incidentAlertUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId, alertB))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId));

        // os dois aparecem na coleção do incidente
        mockMvc.perform(get(incidentAlertsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        assertThat(alertRepository.searchByIncidentId(
                        UUID.fromString(incidentId),
                        org.springframework.data.domain.PageRequest.of(0, 100))
                .getTotalElements())
                .as("N alertas por incidente")
                .isEqualTo(2);

        // e a timeline registra UM ALERT_LINKED por vínculo
        assertThat(eventTypes(incidentId))
                .containsExactly(
                        com.sentinel.project.incident.model.IncidentEventType.CREATED,
                        com.sentinel.project.incident.model.IncidentEventType.ALERT_LINKED,
                        com.sentinel.project.incident.model.IncidentEventType.ALERT_LINKED);
    }

    // --- 409 ALERT_ALREADY_LINKED -------------------------------------------

    @Test
    void linkingAlertOwnedByAnotherIncidentReturns409() throws Exception {
        Linked linked = linkedScene("incident-link-owned");
        String bearer = linked.scene().org().ownerBearer();
        String otherIncident = createIncident(
                bearer, linked.scene().org().organizationId(), linked.scene().projectId(),
                linked.scene().serviceId(), "Outro incidente");

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        mockMvc.perform(putCsrf(url(linked, otherIncident))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_ALREADY_LINKED"));

        // o dono original não foi deslocado
        assertThat(alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow()
                        .getIncident().getId())
                .isEqualTo(UUID.fromString(linked.incidentId()));
    }

    // --- 404 quando o alerta é de outro serviço -----------------------------

    @Test
    void linkingAlertFromAnotherServiceReturns404() throws Exception {
        Linked linked = linkedScene("incident-link-foreign");
        String bearer = linked.scene().org().ownerBearer();

        String sibling = createService(
                bearer, linked.scene().org().organizationId(), linked.scene().projectId(),
                "Serviço B");
        String incidentInB = createIncident(
                bearer, linked.scene().org().organizationId(), linked.scene().projectId(),
                sibling, "No serviço B");

        String forged = incidentAlertUrl(
                linked.scene().org().organizationId(), linked.scene().projectId(),
                sibling, incidentInB, linked.alertId());

        mockMvc.perform(putCsrf(forged).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        assertThat(alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow()
                        .getIncident())
                .isNull();
    }

    // --- DELETE: 204 + idempotência -----------------------------------------

    @Test
    void deleteUnlinksEmitsEventAndSecondDeleteIsSilent() throws Exception {
        Linked linked = linkedScene("incident-link-delete");
        String bearer = linked.scene().org().ownerBearer();

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        mockMvc.perform(deleteCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        assertThat(alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow()
                        .getIncident())
                .as("o vínculo foi removido")
                .isNull();

        mockMvc.perform(get(incidentAlertsUrl(
                                linked.scene().org().organizationId(),
                                linked.scene().projectId(),
                                linked.scene().serviceId(), linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(eventTypes(linked.incidentId()))
                .contains(
                        com.sentinel.project.incident.model.IncidentEventType.CREATED,
                        com.sentinel.project.incident.model.IncidentEventType.ALERT_LINKED,
                        com.sentinel.project.incident.model.IncidentEventType.ALERT_UNLINKED);

        int eventsAfterUnlink = eventsOf(linked.incidentId()).size();

        // segunda vez: 204 de novo, sem evento novo
        mockMvc.perform(deleteCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        assertThat(eventsOf(linked.incidentId())).hasSize(eventsAfterUnlink);
    }

    @Test
    void unlinkingAnAlertThatIsNotLinkedReturns204WithoutEvent() throws Exception {
        Linked linked = linkedScene("incident-link-loose");
        String bearer = linked.scene().org().ownerBearer();
        int eventsBefore = eventsOf(linked.incidentId()).size();

        mockMvc.perform(deleteCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        assertThat(eventsOf(linked.incidentId())).hasSize(eventsBefore);
    }

    // --- RESOLVED congela a associação --------------------------------------

    @Test
    void resolvedIncidentRejectsLinkAndUnlinkWith409() throws Exception {
        Linked linked = linkedScene("incident-link-resolved");
        String bearer = linked.scene().org().ownerBearer();

        mockMvc.perform(patchCsrf(incidentStatusUrl(
                                        linked.scene().org().organizationId(),
                                        linked.scene().projectId(),
                                        linked.scene().serviceId(), linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk());

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_RESOLVED"))
                .andExpect(jsonPath("$.message")
                        .value("Incident is resolved and can no longer be modified"));

        mockMvc.perform(deleteCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_RESOLVED"));

        assertThat(alertRepository.findById(UUID.fromString(linked.alertId())).orElseThrow()
                        .getIncident())
                .isNull();
    }

    // --- paginação da coleção -----------------------------------------------

    @Test
    void incidentAlertCollectionIsPaginatedWithMaxSize100() throws Exception {
        Linked linked = linkedScene("incident-link-paging");
        String bearer = linked.scene().org().ownerBearer();
        String collection = incidentAlertsUrl(
                linked.scene().org().organizationId(), linked.scene().projectId(),
                linked.scene().serviceId(), linked.incidentId());

        mockMvc.perform(putCsrf(url(linked, linked.incidentId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        mockMvc.perform(get(collection + "?page=0&size=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get(collection + "?page=0&size=101")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));
    }
}
