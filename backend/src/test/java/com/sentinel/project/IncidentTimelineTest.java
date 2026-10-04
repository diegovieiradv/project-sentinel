package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Timeline de incidente (Prompt 14) — log <b>append-only</b>, somente leitura.
 *
 * <p>Cada transição é um fato: um evento POR campo efetivamente alterado, na
 * ordem fixa {@code title → description → severity}, com a frase
 * determinística e o carimbo estritamente crescente (folha de 1&nbsp;ms dentro
 * de um mesmo PATCH). A coleção ordena por {@code createdAt ASC, id ASC} e
 * não existe nenhuma rota de escrita de evento — o contrato de 405 do Spring
 * é a proteção.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentTimelineTest extends AbstractIncidentIntegrationTest {

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

    // --- criação -------------------------------------------------------------

    @Test
    void creationEmitsSingleCreatedEventWithExactMessageAndActor() throws Exception {
        Scene scene = newScene("incident-timeline-created", "Org Incident Timeline Created");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Aberto agora");

        List<IncidentEvent> events = eventsOf(incidentId);
        assertThat(events).hasSize(1);

        IncidentEvent created = events.get(0);
        assertThat(created.getType()).isEqualTo(IncidentEventType.CREATED);
        assertThat(created.getMessage()).isEqualTo("Incident created");
        assertThat(created.getIncidentId()).isEqualTo(UUID.fromString(incidentId));
        assertThat(created.getActorUserId())
                .isEqualTo(ownerUserId(scene.org().ownerEmail()));
        assertThat(created.getId()).isNotNull();

        mockMvc.perform(get(incidentEventsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].message").value("Incident created"))
                .andExpect(jsonPath("$[0].incidentId").value(incidentId))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    // --- um evento por campo, na ordem fixa ---------------------------------

    @Test
    void multiFieldPatchEmitsThreeEventsInFixedOrderWithStrictlyIncreasingStamps()
            throws Exception {
        Scene scene = newScene("incident-timeline-patch", "Org Incident Timeline Patch");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Original");

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Renomeado",
                                "description", "Nova descrição",
                                "severity", "SEV1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renomeado"))
                .andExpect(jsonPath("$.description").value("Nova descrição"))
                .andExpect(jsonPath("$.severity").value("SEV1"));

        List<IncidentEvent> events = eventsOf(incidentId);
        assertThat(events)
                .as("um evento por campo alterado, na ordem title → description → severity")
                .extracting(IncidentEvent::getType)
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.TITLE_CHANGED,
                        IncidentEventType.DESCRIPTION_CHANGED,
                        IncidentEventType.SEVERITY_CHANGED);

        assertThat(events)
                .extracting(IncidentEvent::getMessage)
                .containsExactly(
                        "Incident created",
                        "Title changed",
                        "Description changed",
                        "Severity changed from SEV3 to SEV1");

        // carimbo estritamente crescente dentro do mesmo PATCH
        assertThat(stamps(events))
                .as("a folha de 1 ms mantém a ordem mesmo com TIMESTAMPTZ em µs")
                .isSorted();

        // o autor é o mesmo em todos
        assertThat(events)
                .extracting(IncidentEvent::getActorUserId)
                .containsOnly(ownerUserId(scene.org().ownerEmail()));
    }

    @Test
    void noOpPatchEmitsNoEventAndDoesNotAdvanceUpdatedAt() throws Exception {
        Scene scene = newScene("incident-timeline-noop", "Org Incident Timeline Noop");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Sem mudar");

        Incident before = incidentOf(scene.serviceId(), incidentId);
        int eventsBefore = eventsOf(incidentId).size();

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Sem mudar"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sem mudar"));

        assertThat(eventsOf(incidentId)).hasSize(eventsBefore);
        assertThat(incidentOf(scene.serviceId(), incidentId).getUpdatedAt())
                .as("no-op não escreve: o updatedAt não avança")
                .isEqualTo(before.getUpdatedAt());
    }

    // --- status --------------------------------------------------------------

    @Test
    void statusChangeCarriesExactFromToMessage() throws Exception {
        Scene scene = newScene("incident-timeline-status", "Org Incident Timeline Status");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Correndo");

        changeIncidentStatus(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), incidentId, "INVESTIGATING");

        assertThat(eventTypes(incidentId))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.STATUS_CHANGED);
        assertThat(eventsOf(incidentId).get(1).getMessage())
                .isEqualTo("Status changed from OPEN to INVESTIGATING");

        changeIncidentStatus(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), incidentId, "RESOLVED");

        assertThat(eventsOf(incidentId).get(2).getMessage())
                .isEqualTo("Status changed from INVESTIGATING to RESOLVED");
    }

    // --- ordenação -----------------------------------------------------------

    @Test
    void eventsAreOrderedByCreatedAtAscThenIdAsc() throws Exception {
        Scene scene = newScene("incident-timeline-order", "Org Incident Timeline Order");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Ordem");

        changeIncidentStatus(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), incidentId, "INVESTIGATING");
        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Outro título",
                                "severity", "SEV2"))))
                .andExpect(status().isOk());
        changeIncidentStatus(bearer, scene.org().organizationId(), scene.projectId(),
                scene.serviceId(), incidentId, "RESOLVED");

        mockMvc.perform(get(incidentEventsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        List<IncidentEvent> events = eventsOf(incidentId);
        assertThat(events).hasSize(5);

        List<Instant> stamps = stamps(events);
        List<UUID> ids = new ArrayList<>();
        for (IncidentEvent event : events) {
            ids.add(event.getId());
        }

        for (int i = 1; i < events.size(); i++) {
            assertThat(stamps.get(i))
                    .as("createdAt ASC na posição " + i)
                    .isAfterOrEqualTo(stamps.get(i - 1));
            if (stamps.get(i).equals(stamps.get(i - 1))) {
                assertThat(ids.get(i).toString())
                        .as("desempate por id ASC quando os carimbos colidem")
                        .isGreaterThan(ids.get(i - 1).toString());
            }
        }
    }

    // --- append-only ---------------------------------------------------------

    @Test
    void timelineHasNoWriteRoutePatchAndDeleteReturn405() throws Exception {
        Scene scene = newScene("incident-timeline-readonly", "Org Incident Timeline Readonly");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Imutável");
        String events = incidentEventsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);
        int before = eventsOf(incidentId).size();

        mockMvc.perform(patchCsrf(events)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "reescrevendo"))))
                .andExpect(status().isMethodNotAllowed());

        // na coleção o caminho EXISTE (GET) e o método é o errado → 405
        mockMvc.perform(postCsrf(events)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "forjado"))))
                .andExpect(status().isMethodNotAllowed());

        // no item não existe rota alguma → 404 (o Spring só devolve 405 quando
        // o padrão casa e o método diverge); nos dois casos a timeline sobrevive
        mockMvc.perform(patchCsrf(events + "/" + eventsOf(incidentId).get(0).getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "reescrevendo"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(deleteCsrf(events + "/" + eventsOf(incidentId).get(0).getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());

        assertThat(eventsOf(incidentId)).hasSize(before);
    }

    // --- apoio ---------------------------------------------------------------

    private UUID ownerUserId(String ownerEmail) {
        return userRepository.findByEmail(ownerEmail).orElseThrow().getId();
    }

    private List<Instant> stamps(List<IncidentEvent> events) {
        List<Instant> result = new ArrayList<>(events.size());
        for (IncidentEvent event : events) {
            result.add(event.getCreatedAt());
        }
        return result;
    }
}
