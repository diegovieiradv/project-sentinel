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
 * Fronteira anti-IDOR dos incidentes (Prompt 14) contra contexto real.
 *
 * <p>A cadeia Organization → Project → Service → Incident é resolvida com
 * consultas tenant-scoped: qualquer pedaço alheio (org, projeto, serviço ou
 * incidente) responde o MESMO 404 genérico — em leitura E em escrita — sem
 * detalhar a existência do recurso e sem gravar nada. O alerta vinculado
 * também precisa pertencer ao MESMO serviço, senão o link responde 404.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class IncidentIdorTest extends AbstractIncidentIntegrationTest {

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

    // --- cenário: dois tenants ----------------------------------------------

    private record Pair(Scene alpha, String alphaIncident, Scene beta, String betaIncident) {
    }

    private Pair buildPair() throws Exception {
        Scene alpha = newScene("incident-idor-alpha", "Org Incident Alpha");
        String alphaIncident = createIncident(
                alpha.org().ownerBearer(),
                alpha.org().organizationId(), alpha.projectId(), alpha.serviceId(),
                "Incidente Alpha");

        Scene beta = newScene("incident-idor-beta", "Org Incident Beta");
        String betaIncident = createIncident(
                beta.org().ownerBearer(),
                beta.org().organizationId(), beta.projectId(), beta.serviceId(),
                "Incidente Beta");

        return new Pair(alpha, alphaIncident, beta, betaIncident);
    }

    // --- 1: incidente de outro tenant ---------------------------------------

    @Test
    void incidentFromOtherOrgReturns404OnReadAndWrite() throws Exception {
        Pair pair = buildPair();

        expect404(incidentUrl(
                        pair.beta().org().organizationId(), pair.beta().projectId(),
                        pair.beta().serviceId(), pair.alphaIncident),
                pair.beta().org().ownerBearer());

        mockMvc.perform(patchCsrf(incidentUrl(
                                        pair.beta().org().organizationId(), pair.beta().projectId(),
                                        pair.beta().serviceId(), pair.alphaIncident))
                        .header(HttpHeaders.AUTHORIZATION, pair.beta().org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Tomado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // o caminho do PRÓPRIO tenant Beta continua com o incidente dele
        mockMvc.perform(get(incidentUrl(
                                pair.beta().org().organizationId(), pair.beta().projectId(),
                                pair.beta().serviceId(), pair.betaIncident))
                        .header(HttpHeaders.AUTHORIZATION, pair.beta().org().ownerBearer()))
                .andExpect(status().isOk());
    }

    @Test
    void foreignUserOnOwnPathReturns404FromInterceptor() throws Exception {
        Pair pair = buildPair();

        mockMvc.perform(get(incidentUrl(
                                pair.alpha().org().organizationId(), pair.alpha().projectId(),
                                pair.alpha().serviceId(), pair.alphaIncident))
                        .header(HttpHeaders.AUTHORIZATION, pair.beta().org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- 2/3: projeto e serviço de outro tenant no caminho próprio -----------

    @Test
    void foreignProjectInsideOwnOrgPathReturns404() throws Exception {
        Pair pair = buildPair();

        String forged = "/api/v1/organizations/"
                + pair.beta().org().organizationId()
                + "/projects/"
                + pair.alpha().projectId()
                + "/services/"
                + pair.beta().serviceId()
                + "/incidents";

        expect404(forged, pair.beta().org().ownerBearer());
    }

    @Test
    void foreignServiceInsideOwnPathReturns404() throws Exception {
        Pair pair = buildPair();

        String forged = "/api/v1/organizations/"
                + pair.beta().org().organizationId()
                + "/projects/"
                + pair.beta().projectId()
                + "/services/"
                + pair.alpha().serviceId()
                + "/incidents";

        expect404(forged, pair.beta().org().ownerBearer());
    }

    // --- 4: incidente de outro serviço irmão --------------------------------

    @Test
    void incidentFromSiblingServiceReturns404AndWritesNothing() throws Exception {
        Scene scene = newScene("incident-idor-siblings", "Org Incident Siblings");
        String incidentId = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Do serviço A");
        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(),
                "Serviço B");

        expect404(incidentUrl(
                        scene.org().organizationId(), scene.projectId(), sibling, incidentId),
                scene.org().ownerBearer());

        mockMvc.perform(patchCsrf(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        sibling, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Tomado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        assertThat(incidentOf(scene.serviceId(), incidentId).getTitle())
                .isEqualTo("Do serviço A");
    }

    // --- 5: UUID válido fora da cadeia e malformado -------------------------

    @Test
    void validUuidNotInChainReturns404() throws Exception {
        Scene scene = newScene("incident-idor-random", "Org Incident Random");

        expect404(incidentUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                        UUID.randomUUID().toString()),
                scene.org().ownerBearer());
    }

    @Test
    void malformedIncidentIdReturns404Never500() throws Exception {
        Scene scene = newScene("incident-idor-malformed", "Org Incident Malformed");

        expect404(incidentUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                        "not-a-uuid"),
                scene.org().ownerBearer());

        // id malformado no PATCH também é 404 genérico, nunca 400/500
        mockMvc.perform(patchCsrf(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "x"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- 6: alerta de outro serviço no link ---------------------------------

    @Test
    void linkAlertFromAnotherServiceReturns404() throws Exception {
        Scene scene = newScene("incident-idor-alert", "Org Incident Alert");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor A");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra A");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);
        evaluate(scene.serviceId());
        String alertA = singleAlertOf(scene.serviceId()).getId().toString();

        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(),
                "Serviço B");
        String incidentInB = createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(), scene.projectId(), sibling,
                "No serviço B");

        // o alerta pertence ao serviço A, o incidente ao serviço B
        mockMvc.perform(putCsrf(incidentAlertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        sibling, incidentInB, alertA))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        mockMvc.perform(get(incidentAlertsUrl(
                                scene.org().organizationId(), scene.projectId(),
                                sibling, incidentInB))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // e o alerta do serviço A continua solto (sem vínculo)
        assertThat(alertRepository
                        .findById(UUID.fromString(alertA))
                        .orElseThrow()
                        .getIncident())
                .isNull();
    }

    // --- apoio ---------------------------------------------------------------

    private void expect404(String url, String bearer) throws Exception {
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }
}
