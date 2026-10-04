package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sentinel.project.incident.model.IncidentEvent;
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
 * Duas corridas do Prompt 14, ambas com desfecho DETERMINÍSTICO.
 *
 * <ol>
 *   <li><b>Status</b> — dois PATCH simultâneos para o MESMO alvo partindo de
 *       {@code OPEN}. Não-idempotência (critério 17) + {@code @Version} da V11
 *       garantem {200, 409}: quem lê depois já está no alvo e o no-op é erro;
 *       quem lê antes disputa a versão e perde com 409 CONFLICT. O vencedor é
 *       único e a timeline tem UM {@code STATUS_CHANGED}.</li>
 *   <li><b>Vínculo de alerta</b> — o mesmo alerta disputado por DOIS
 *       incidentes do serviço. O UPDATE condicional
 *       ({@code WHERE incident_id IS NULL}) é a autoridade: só uma linha muda
 *       e o perdedor re-le e recebe {@code 409 ALERT_ALREADY_LINKED} — nunca
 *       dois vínculos, nunca alerta "órfão" com dono silencioso.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class IncidentConcurrencyTest extends AbstractIncidentIntegrationTest {

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

    @Test
    void concurrentStatusChangeToSameTargetResolvesToOne200AndOne409() throws Exception {
        Scene scene = newScene("incident-conc-status", "Org Incident Conc Status");
        String bearer = scene.org().ownerBearer();
        String incidentId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Corrida de status");
        String url = incidentStatusUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);
        String payload = json(Map.of("status", "INVESTIGATING"));

        List<Integer> statuses = concurrentPatches(bearer, url, payload, bearer, url, payload);

        // o no-op não vira 200: só um vencedor e um perdedor
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        assertThat(incidentOf(scene.serviceId(), incidentId).getStatus().name())
                .isEqualTo("INVESTIGATING");

        List<IncidentEvent> events = eventsOf(incidentId);
        assertThat(events)
                .filteredOn(event -> event.getType().name().equals("STATUS_CHANGED"))
                .hasSize(1)
                .first()
                .extracting(IncidentEvent::getMessage)
                .isEqualTo("Status changed from OPEN to INVESTIGATING");
    }

    @Test
    void concurrentLinkOfSameAlertToTwoIncidentsResolvesToExactlyOneLink() throws Exception {
        Scene scene = newScene("incident-conc-link", "Org Incident Conc Link");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor Corrida");
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra Corrida");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);
        evaluate(scene.serviceId());
        String alertId = singleAlertOf(scene.serviceId()).getId().toString();

        String incidentA = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente A");
        String incidentB = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente B");

        List<Integer> statuses = concurrentPuts(
                bearer,
                incidentAlertUrl(scene.org().organizationId(), scene.projectId(),
                        scene.serviceId(), incidentA, alertId),
                bearer,
                incidentAlertUrl(scene.org().organizationId(), scene.projectId(),
                        scene.serviceId(), incidentB, alertId));

        // UPDATE condicional + releitura (sem índice único) produzem UM vencedor
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        UUID owner = alertRepository.findById(UUID.fromString(alertId)).orElseThrow()
                .getIncident().getId();
        assertThat(owner)
                .as("o alerta deve ficar em UM incidente — nunca solto, nunca em dois")
                .isIn(UUID.fromString(incidentA), UUID.fromString(incidentB));

        // o vencedor tem 1, o perdedor 0: a soma é exatamente 1
        assertThat(countAlerts(incidentA) + countAlerts(incidentB)).isEqualTo(1);

        // e o ALERT_LINKED foi emitido UMA vez só
        List<IncidentEvent> linkedEvents = new ArrayList<>();
        linkedEvents.addAll(eventsOf(incidentA));
        linkedEvents.addAll(eventsOf(incidentB));
        assertThat(linkedEvents)
                .filteredOn(event -> event.getType().name().equals("ALERT_LINKED"))
                .hasSize(1);
    }

    // --- apoio ---------------------------------------------------------------

    private long countAlerts(String incidentId) {
        return alertRepository.searchByIncidentId(
                        UUID.fromString(incidentId),
                        org.springframework.data.domain.PageRequest.of(0, 100))
                .getTotalElements();
    }

    private record Leg(String bearer, String url, String payload) {
    }

    /** Dois PATCHs simultâneos na MESMA URL — devolve os status. */
    private List<Integer> concurrentPatches(
            String bearerA, String urlA, String payloadA,
            String bearerB, String urlB, String payloadB) throws Exception {
        return runConcurrently(
                new Leg(bearerA, urlA, payloadA), new Leg(bearerB, urlB, payloadB), "patch");
    }

    /** Dois PUTs simultâneos (mesmo alerta, URLs de incidentes distintos). */
    private List<Integer> concurrentPuts(
            String bearerA, String urlA, String bearerB, String urlB) throws Exception {
        return runConcurrently(new Leg(bearerA, urlA, "{}"), new Leg(bearerB, urlB, "{}"), "put");
    }

    private List<Integer> runConcurrently(Leg first, Leg second, String verb) throws Exception {
        // CSRF materializado ANTES das threads (happen-before da leitura cacheada)
        csrf();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>(2);
            for (Leg leg : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                            builder = "put".equals(verb)
                                    ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                            .put(leg.url())
                                    : org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                            .patch(leg.url());
                    return mockMvc.perform(builder
                                    .header(HttpHeaders.AUTHORIZATION, leg.bearer())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(leg.payload()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
