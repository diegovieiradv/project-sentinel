package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.model.IncidentStatus;
import com.sentinel.project.model.Service;
import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Painel operacional do projeto (Prompt 16):
 * {@code GET /organizations/{org}/projects/{project}/dashboard?range=24h}.
 *
 * <p>Cobertura: cadeia de acesso (401 anônimo, 405 em POST, 404 genérico de
 * id malformado/inexistente/de outro tenant ANTES do 400 de range, leitura
 * livre para ADMIN/OPERATOR/VIEWER), contrato exato das dez chaves de topo e
 * de cada seção sem vazamento de domínio alheio, saúde por status (contando
 * serviço desligado e estado vazio), disponibilidade global do Prompt 11
 * (agregação, nunca média de médias; latência só de SUCCESS/DEGRADED;
 * {@code sentinel_internal_failure} fora do denominador; faixa escolhendo a
 * janela), alertas (GROUP BY de severidade + 10 primeiros, fora da janela os
 * resolvidos e de outro projeto), incidentes (status ativos, contagem de
 * alertas associados, severidade na ordenação), serviços pedindo atenção
 * (filtro, ordenação estado → severidade → nome, {@code LIMIT 20}), atividade
 * recente (UNION das seis fontes, janela semi-aberta, ordenação e
 * {@code LIMIT 20}), invariantes de soma, volume de 50 serviços, leitura com
 * pais inativos e isolamento por projeto/tenant.</p>
 *
 * <p>Relógio congelado pelo {@link FixedMetricsClockConfig}: a janela de 24h
 * é {@code [2026-02-28T12:00:00Z, 2026-03-01T12:00:00Z)} e TODA a semeadura
 * usa instantes explícitos dentro dela — timestamps de {@code @PrePersist}
 * ({@code Instant.now()} real) cairiam FORA da janela.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class DashboardApiTest extends AbstractIncidentIntegrationTest {

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

    /** Janela de 24h do relógio congelado (12:34:56 truncado para 12:00). */
    private static final Instant TO_24H = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant FROM_24H = Instant.parse("2026-02-28T12:00:00Z");

    // --- URLs e disparo -----------------------------------------------------

    private String dashboardUrl(String organizationId, String projectId) {
        return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/dashboard";
    }

    private String dashboardUrl(Scene scene) {
        return dashboardUrl(scene.org().organizationId(), scene.projectId());
    }

    private MockHttpServletRequestBuilder dashboardGet(String bearer, String url) {
        return get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    /** GET → 200 com o corpo cru (asserções de formato: 80.00, null, ...). */
    private String dashboardBody(String bearer, String url) throws Exception {
        return mockMvc.perform(dashboardGet(bearer, url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** GET → 200 com o corpo já mapeado (asserções de chave/estrutura). */
    private Map<String, Object> dashboardJson(String bearer, String url) throws Exception {
        MvcResult result = mockMvc.perform(dashboardGet(bearer, url))
                .andExpect(status().isOk())
                .andReturn();
        return responseBody(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    /**
     * Linhas de uma seção ({@code body.secao.chave}) OU de uma lista de topo
     * ({@code body.lista}) — o helper aceita as duas formas do contrato.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String section, String key) {
        Object holder = body.get(section);
        if (holder instanceof List) {
            return (List<Map<String, Object>>) holder;
        }
        return (List<Map<String, Object>>) ((Map<String, Object>) holder).get(key);
    }

    private static int num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).intValue();
    }

    // --- semeadura ----------------------------------------------------------

    /** Um check no instante EXPLÍCITO (a janela do painel é semi-aberta). */
    private void seedCheck(
            String serviceId, String monitorId, CheckOutcome outcome, Instant at, long durationMillis) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        boolean healthy = outcome == CheckOutcome.SUCCESS || outcome == CheckOutcome.DEGRADED_SIGNAL;
        monitorCheckRepository.save(new MonitorCheck(
                monitor,
                at,
                at.plusMillis(durationMillis),
                durationMillis,
                healthy ? 200 : null,
                outcome,
                outcome == CheckOutcome.TARGET_FAILURE
                        ? CheckFailureCategory.TIMEOUT
                        : outcome == CheckOutcome.SENTINEL_INTERNAL_FAILURE
                                ? CheckFailureCategory.INTERNAL
                                : null,
                "seeded " + outcome));
    }

    /** {@code count} checks do mesmo outcome a partir de {@code from} (+1s). */
    private void seedChecks(
            String serviceId, String monitorId, CheckOutcome outcome, int count, Instant from) {
        for (int i = 0; i < count; i++) {
            seedCheck(serviceId, monitorId, outcome, from.plusSeconds(i), 100);
        }
    }

    /** Regra FIRING própria (a tabela tem unicidade de regra aberta). */
    private Alert seedAlert(String serviceId, AlertSeverity severity, Instant firedAt) {
        return seedAlert(
                serviceId,
                severity,
                firedAt,
                "alerta semeado " + severity,
                "Regra " + UUID.randomUUID());
    }

    private Alert seedAlert(
            String serviceId,
            AlertSeverity severity,
            Instant firedAt,
            String message,
            String ruleName) {
        Service service = serviceOf(serviceId);
        AlertRule rule = alertRuleRepository.save(new AlertRule(
                service,
                ruleName,
                null,
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN,
                new BigDecimal("99.9"),
                null,
                EvaluationWindow.FIFTEEN_MINUTES,
                severity));
        return alertRepository.save(new Alert(service, rule, message, new BigDecimal("99.90"), firedAt));
    }

    /** Alerta disparado e resolvido dentro do intervalo pedido. */
    private Alert seedResolvedAlert(
            String serviceId, AlertSeverity severity, Instant firedAt, Instant resolvedAt) {
        Alert alert = seedAlert(serviceId, severity, firedAt);
        alert.resolve(AlertResolutionReason.AUTO, resolvedAt);
        return alertRepository.save(alert);
    }

    private Incident seedIncident(
            String serviceId, String title, AlertSeverity severity, Instant startedAt, UUID createdBy) {
        return incidentRepository.save(
                new Incident(serviceOf(serviceId), title, null, severity, startedAt, createdBy));
    }

    /** Evento da timeline com instante EXPLÍCITO (o @PrePersist usaria o real). */
    private void saveEvent(
            Incident incident, IncidentEventType type, String message, UUID actor, Instant at) {
        incidentEventRepository.save(new IncidentEvent(incident.getId(), type, message, actor, at));
    }

    /** Vínculo incidente ↔ alerta pela API de produção (precisa de pai ativo). */
    private void linkAlert(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String incidentId,
            String alertId)
            throws Exception {
        mockMvc.perform(putCsrf(incidentsUrl(organizationId, projectId, serviceId)
                        + "/" + incidentId
                        + "/alerts/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }

    private Service serviceOf(String serviceId) {
        return serviceRepository.findById(UUID.fromString(serviceId)).orElseThrow();
    }

    private UUID ownerUserId(Scene scene) {
        return userRepository.findByEmail(scene.org().ownerEmail()).orElseThrow().getId();
    }

    // --- acesso ---------------------------------------------------------------

    @Test
    void anonymousReadReturns401() throws Exception {
        Scene scene = newScene("dash-auth", "Dash Auth Corp");

        mockMvc.perform(get(dashboardUrl(scene)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void writeVerbsReturn405BecauseThePanelIsReadOnly() throws Exception {
        Scene scene = newScene("dash-method", "Dash Method Corp");

        mockMvc.perform(postCsrf(dashboardUrl(scene))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method POST not allowed"));
    }

    @Test
    void unknownMalformedOrForeignProjectReturnsGeneric404() throws Exception {
        Scene scene = newScene("dash-404", "Dash 404 Corp");
        Fixture foreign = newOrganization(uniqueEmail("dash-foreign"), "Dash Foreign Corp");
        String foreignProject = createProject(foreign.ownerBearer(), foreign.organizationId(), "Alheio");
        String own = scene.org().ownerBearer();
        String ownOrg = scene.org().organizationId();

        // id malformado nem chega ao repositório
        mockMvc.perform(dashboardGet(own, dashboardUrl(ownOrg, "nao-e-uuid")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // projeto inexistente
        mockMvc.perform(dashboardGet(own, dashboardUrl(ownOrg, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // projeto de OUTRO tenant apresentado sob o caminho do próprio
        mockMvc.perform(dashboardGet(own, dashboardUrl(ownOrg, foreignProject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // ORDEM do contrato: a cadeia (404) vale mais que range inválido (400)
        mockMvc.perform(dashboardGet(own, dashboardUrl(ownOrg, UUID.randomUUID().toString()) + "?range=48h"))
                .andExpect(status().isNotFound());

        // controle: caminho certo responde com a janela normal
        mockMvc.perform(dashboardGet(own, dashboardUrl(scene)))
                .andExpect(status().isOk());
    }

    @Test
    void unknownRangeReturns400WithTheCanonicalMessage() throws Exception {
        Scene scene = newScene("dash-range-400", "Dash Range 400 Corp");

        mockMvc.perform(dashboardGet(scene.org().ownerBearer(), dashboardUrl(scene) + "?range=999h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("range: must be one of 1h, 6h, 24h, 7d, 30d"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void everyAuthenticatedRoleCanReadThePanel() throws Exception {
        Scene scene = newScene("dash-roles", "Dash Roles Corp");
        String bearer = scene.org().ownerBearer();

        String operatorEmail = uniqueEmail("dash-operator");
        String operator = registerAndLogin("Dash Operator", operatorEmail);
        addMember(bearer, scene.org().organizationId(), operatorEmail, "OPERATOR");

        String viewerEmail = uniqueEmail("dash-viewer");
        String viewer = registerAndLogin("Dash Viewer", viewerEmail);
        addMember(bearer, scene.org().organizationId(), viewerEmail, "VIEWER");

        mockMvc.perform(dashboardGet(bearer, dashboardUrl(scene)))
                .andExpect(status().isOk());
        mockMvc.perform(dashboardGet(operator, dashboardUrl(scene)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health.totalServices").value(1));
        mockMvc.perform(dashboardGet(viewer, dashboardUrl(scene)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rangeKey").value("24h"));
    }

    // --- contrato -------------------------------------------------------------

    @Test
    void panelExposesExactlyTheContractedShapeWithoutDomainLeaks() throws Exception {
        Scene scene = newScene("dash-contract", "Dash Contract Corp");
        String bearer = scene.org().ownerBearer();
        UUID owner = ownerUserId(scene);

        forceServiceStatus(scene.serviceId(), "DEGRADED", Instant.parse("2026-03-01T09:00:00Z"));
        seedAlert(scene.serviceId(), AlertSeverity.SEV2, FROM_24H.plusSeconds(60));
        seedIncident(
                scene.serviceId(), "Incidente do contrato", AlertSeverity.SEV3,
                FROM_24H.plusSeconds(120), owner);

        MvcResult result = mockMvc.perform(dashboardGet(bearer, dashboardUrl(scene)))
                .andExpect(status().isOk())
                .andReturn();
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> body = responseBody(result);

        // as dez chaves do contrato, nenhuma a mais e nenhuma a menos
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "projectId", "rangeKey", "windowFrom", "windowTo", "health",
                "availability", "alerts", "incidents", "servicesNeedingAttention", "recentActivity");
        assertThat(body.get("projectId")).isEqualTo(scene.projectId());
        assertThat(body.get("rangeKey")).isEqualTo("24h");
        assertThat(body.get("windowFrom")).isEqualTo(FROM_24H.toString());
        assertThat(body.get("windowTo")).isEqualTo(TO_24H.toString());

        // nenhum vocabulário de outro domínio vaza para a resposta
        assertThat(raw).doesNotContain(
                "\"organizationId\"", "\"monitors\"", "\"buckets\"", "\"bucketSeconds\"",
                "\"breakdown\"", "\"ruleType\"", "\"triggerValue\"", "\"description\"", "\"checks\"");

        assertThat(section(body, "health").keySet()).containsExactlyInAnyOrder(
                "totalServices", "operational", "degraded", "unavailable", "unknown");
        assertThat(section(body, "availability").keySet()).containsExactlyInAnyOrder(
                "availabilityPercent", "totalChecks", "relevantChecks", "successRatePercent",
                "avgLatencyMs", "minLatencyMs", "maxLatencyMs");
        assertThat(section(body, "alerts").keySet()).containsExactlyInAnyOrder(
                "firingTotal", "sev1", "sev2", "sev3", "sev4", "activeAlerts");
        assertThat(section(body, "incidents").keySet()).containsExactlyInAnyOrder(
                "activeTotal", "open", "investigating", "identified", "activeIncidents");

        Map<String, Object> alert = rows(body, "alerts", "activeAlerts").get(0);
        assertThat(alert.keySet()).containsExactlyInAnyOrder(
                "alertId", "serviceId", "serviceName", "ruleId", "ruleName", "severity",
                "firedAt", "incidentId");
        assertThat(alert.get("serviceName")).isEqualTo("Serviço Monitor");

        Map<String, Object> incident = rows(body, "incidents", "activeIncidents").get(0);
        assertThat(incident.keySet()).containsExactlyInAnyOrder(
                "incidentId", "serviceId", "serviceName", "title", "severity", "status",
                "source", "startedAt", "associatedAlertsCount");
        assertThat(incident.get("source")).isEqualTo("MANUAL");

        Map<String, Object> attention = rows(body, "servicesNeedingAttention", "servicesNeedingAttention").get(0);
        assertThat(attention.keySet()).containsExactlyInAnyOrder(
                "serviceId", "serviceName", "status", "highestSeverity", "hasActiveIncident",
                "statusChangedAt");

        Map<String, Object> activity = rows(body, "recentActivity", "recentActivity").get(0);
        assertThat(activity.keySet()).containsExactlyInAnyOrder(
                "type", "timestamp", "serviceId", "serviceName", "severity", "title",
                "resourceId", "resourceType");
    }

    @Test
    void rangeSelectsTheWindowAndRefiltersAvailability() throws Exception {
        Scene scene = newScene("dash-window", "Dash Window Corp");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Faixa");
        // dois checks antigos: dentro da janela de 24h, FORA da de 1h
        seedChecks(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 2, FROM_24H);
        // um check na manhã do relógio congelado: dentro das DUAS janelas
        seedChecks(
                scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1,
                Instant.parse("2026-03-01T11:45:00Z"));

        Map<String, Object> full = dashboardJson(bearer, dashboardUrl(scene));
        assertThat(full.get("rangeKey")).isEqualTo("24h");
        assertThat(full.get("windowFrom")).isEqualTo(FROM_24H.toString());
        assertThat(full.get("windowTo")).isEqualTo(TO_24H.toString());
        assertThat(num(section(full, "availability"), "totalChecks")).isEqualTo(3);

        Map<String, Object> hour = dashboardJson(bearer, dashboardUrl(scene) + "?range=1h");
        assertThat(hour.get("rangeKey")).isEqualTo("1h");
        assertThat(hour.get("windowFrom")).isEqualTo("2026-03-01T11:30:00Z");
        assertThat(hour.get("windowTo")).isEqualTo("2026-03-01T12:30:00Z");
        assertThat(num(section(hour, "availability"), "totalChecks")).isEqualTo(1);
        // contagens de saúde/alerta/incidente não dependem da faixa
        assertThat(num(section(hour, "health"), "totalServices"))
                .isEqualTo(num(section(full, "health"), "totalServices"));
    }

    // --- saúde -----------------------------------------------------------------

    @Test
    void healthCountsEveryServiceStatusIncludingInactiveOnes() throws Exception {
        Scene scene = newScene("dash-health", "Dash Health Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        String operational = createService(bearer, organizationId, projectId, "Saudavel");
        String degraded = createService(bearer, organizationId, projectId, "Degradado");
        String unavailable = createService(bearer, organizationId, projectId, "Quebrado");

        forceServiceStatus(operational, "OPERATIONAL", Instant.parse("2026-03-01T08:00:00Z"));
        forceServiceStatus(degraded, "DEGRADED", Instant.parse("2026-03-01T08:10:00Z"));
        forceServiceStatus(unavailable, "UNAVAILABLE", Instant.parse("2026-03-01T08:20:00Z"));

        // serviço DESLIGADO continua sendo lido pelo painel
        changeActivation(bearer, organizationId, projectId, operational, false);

        Map<String, Object> health = section(
                dashboardJson(bearer, dashboardUrl(scene)), "health");
        assertThat(num(health, "totalServices")).isEqualTo(4);
        assertThat(num(health, "operational")).isEqualTo(1);
        assertThat(num(health, "degraded")).isEqualTo(1);
        assertThat(num(health, "unavailable")).isEqualTo(1);
        assertThat(num(health, "unknown")).isEqualTo(1);
        assertThat(num(health, "operational") + num(health, "degraded")
                        + num(health, "unavailable") + num(health, "unknown"))
                .isEqualTo(num(health, "totalServices"));
    }

    @Test
    void emptyProjectReturns200WithZeroesAndNullAvailability() throws Exception {
        Fixture org = newOrganization(uniqueEmail("dash-empty"), "Dash Empty Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Sem servico");
        String url = dashboardUrl(org.organizationId(), projectId);

        mockMvc.perform(dashboardGet(org.ownerBearer(), url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health.totalServices").value(0))
                .andExpect(jsonPath("$.availability.totalChecks").value(0))
                .andExpect(jsonPath("$.availability.relevantChecks").value(0))
                .andExpect(jsonPath("$.alerts.firingTotal").value(0))
                .andExpect(jsonPath("$.incidents.activeTotal").value(0))
                .andExpect(jsonPath("$.servicesNeedingAttention.length()").value(0))
                .andExpect(jsonPath("$.recentActivity.length()").value(0));

        String raw = dashboardBody(org.ownerBearer(), url);
        assertThat(raw)
                .contains("\"availabilityPercent\":null")
                .contains("\"successRatePercent\":null")
                .contains("\"avgLatencyMs\":null");
    }

    // --- disponibilidade --------------------------------------------------------

    @Test
    void availabilityAggregatesGloballyInsteadOfAveragingPercentages() throws Exception {
        Scene scene = newScene("dash-avail", "Dash Avail Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        String monitorA = createMonitor(
                bearer, organizationId, projectId, scene.serviceId(), "Monitor A");
        seedChecks(scene.serviceId(), monitorA, CheckOutcome.SUCCESS, 3, FROM_24H);

        String serviceB = createService(bearer, organizationId, projectId, "Servico B");
        String monitorB = createMonitor(bearer, organizationId, projectId, serviceB, "Monitor B");
        seedChecks(serviceB, monitorB, CheckOutcome.SUCCESS, 1, FROM_24H);
        seedChecks(serviceB, monitorB, CheckOutcome.TARGET_FAILURE, 1, FROM_24H.plusSeconds(30));

        // agregação global: 4/5 = 80.00 — média de médias (100% e 50%) daria 75.00
        String raw = dashboardBody(bearer, dashboardUrl(scene));
        assertThat(raw)
                .contains("\"totalChecks\":5", "\"relevantChecks\":5")
                .contains("\"availabilityPercent\":80.00", "\"successRatePercent\":80.00");
    }

    @Test
    void sentinelInternalFailuresStayOutOfTheDenominator() throws Exception {
        Scene scene = newScene("dash-internal", "Dash Internal Corp");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Interno");

        seedChecks(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 2, FROM_24H);
        seedCheck(
                scene.serviceId(), monitorId, CheckOutcome.SENTINEL_INTERNAL_FAILURE,
                FROM_24H.plusSeconds(30), 40);

        // total 3, relevante 2 (a falha do SENTINEL fica de fora das fórmulas)
        String raw = dashboardBody(bearer, dashboardUrl(scene));
        assertThat(raw)
                .contains("\"totalChecks\":3", "\"relevantChecks\":2")
                .contains("\"availabilityPercent\":100.00", "\"successRatePercent\":100.00");
    }

    @Test
    void latencyAveragesOnlySuccessfulAndDegradedChecks() throws Exception {
        Scene scene = newScene("dash-latency", "Dash Latency Corp");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Latencia");

        seedCheck(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, FROM_24H, 100);
        seedCheck(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, FROM_24H.plusSeconds(1), 300);

        String raw = dashboardBody(bearer, dashboardUrl(scene));
        assertThat(raw)
                .contains("\"avgLatencyMs\":100.00", "\"minLatencyMs\":100", "\"maxLatencyMs\":100")
                .contains("\"totalChecks\":2", "\"relevantChecks\":2")
                .contains("\"availabilityPercent\":50.00");
    }

    // --- alertas -----------------------------------------------------------------

    @Test
    void alertsAggregateBySeverityAndReturnOnlyTheTenMostSevere() throws Exception {
        Scene scene = newScene("dash-alerts", "Dash Alerts Corp");
        String bearer = scene.org().ownerBearer();

        seedAlert(scene.serviceId(), AlertSeverity.SEV1, FROM_24H.plusSeconds(10));
        for (int i = 0; i < 3; i++) {
            seedAlert(scene.serviceId(), AlertSeverity.SEV2, FROM_24H.plusSeconds(20 + i));
        }
        for (int i = 0; i < 8; i++) {
            seedAlert(scene.serviceId(), AlertSeverity.SEV3, FROM_24H.plusSeconds(40 + i));
        }

        Map<String, Object> alerts = section(dashboardJson(bearer, dashboardUrl(scene)), "alerts");
        assertThat(num(alerts, "firingTotal")).isEqualTo(12);
        assertThat(num(alerts, "sev1")).isEqualTo(1);
        assertThat(num(alerts, "sev2")).isEqualTo(3);
        assertThat(num(alerts, "sev3")).isEqualTo(8);
        assertThat(num(alerts, "sev4")).isEqualTo(0);

        List<Map<String, Object>> top = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "alerts", "activeAlerts");
        assertThat(top).hasSize(10);
        assertThat(top.get(0).get("severity")).isEqualTo("SEV1");
        assertThat(top.subList(1, 4))
                .allSatisfy(row -> assertThat(row.get("severity")).isEqualTo("SEV2"));
        assertThat(top.subList(4, 10))
                .allSatisfy(row -> assertThat(row.get("severity")).isEqualTo("SEV3"));
        // dentro da mesma severidade: mais recente primeiro
        assertThat(top.get(4).get("firedAt")).isEqualTo(FROM_24H.plusSeconds(47).toString());
        assertThat(top.get(5).get("firedAt")).isEqualTo(FROM_24H.plusSeconds(46).toString());
    }

    @Test
    void resolvedAlertsAndAlertsFromOtherProjectsAreIgnored() throws Exception {
        Scene scene = newScene("dash-alert-scope", "Dash Alert Scope Corp");
        Scene other = newScene("dash-alert-other", "Dash Alert Other Corp");
        String bearer = scene.org().ownerBearer();

        seedAlert(scene.serviceId(), AlertSeverity.SEV2, FROM_24H.plusSeconds(5));
        seedResolvedAlert(
                scene.serviceId(), AlertSeverity.SEV1, FROM_24H.plusSeconds(10),
                FROM_24H.plusSeconds(20));
        seedAlert(other.serviceId(), AlertSeverity.SEV1, FROM_24H.plusSeconds(30));

        Map<String, Object> alerts = section(dashboardJson(bearer, dashboardUrl(scene)), "alerts");
        assertThat(num(alerts, "firingTotal")).isEqualTo(1);
        assertThat(num(alerts, "sev1")).isEqualTo(0);
        assertThat(num(alerts, "sev2")).isEqualTo(1);
        assertThat(rows(dashboardJson(bearer, dashboardUrl(scene)), "alerts", "activeAlerts"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("ruleName")).isNotNull());
    }

    // --- incidentes ---------------------------------------------------------------

    @Test
    void incidentsCountActiveStatusesAndExcludeResolvedOnes() throws Exception {
        Scene scene = newScene("dash-incidents", "Dash Incidents Corp");
        String bearer = scene.org().ownerBearer();
        UUID owner = ownerUserId(scene);

        seedIncident(scene.serviceId(), "Aberto", AlertSeverity.SEV3, FROM_24H.plusSeconds(10), owner);
        Incident investigating = seedIncident(
                scene.serviceId(), "Investigando", AlertSeverity.SEV2, FROM_24H.plusSeconds(20), owner);
        investigating.transitionTo(IncidentStatus.INVESTIGATING, FROM_24H.plusSeconds(30));
        incidentRepository.save(investigating);
        Incident identified = seedIncident(
                scene.serviceId(), "Identificado", AlertSeverity.SEV1, FROM_24H.plusSeconds(40), owner);
        identified.transitionTo(IncidentStatus.IDENTIFIED, FROM_24H.plusSeconds(50));
        incidentRepository.save(identified);
        Incident resolved = seedIncident(
                scene.serviceId(), "Resolvido", AlertSeverity.SEV4, FROM_24H.plusSeconds(60), owner);
        resolved.transitionTo(IncidentStatus.RESOLVED, FROM_24H.plusSeconds(70));
        incidentRepository.save(resolved);

        Map<String, Object> incidents = section(dashboardJson(bearer, dashboardUrl(scene)), "incidents");
        assertThat(num(incidents, "activeTotal")).isEqualTo(3);
        assertThat(num(incidents, "open")).isEqualTo(1);
        assertThat(num(incidents, "investigating")).isEqualTo(1);
        assertThat(num(incidents, "identified")).isEqualTo(1);
        assertThat(num(incidents, "open") + num(incidents, "investigating") + num(incidents, "identified"))
                .isEqualTo(num(incidents, "activeTotal"));

        List<Map<String, Object>> top = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "incidents", "activeIncidents");
        assertThat(top).extracting(row -> row.get("severity"))
                .containsExactly("SEV1", "SEV2", "SEV3");
        assertThat(top).extracting(row -> row.get("title"))
                .doesNotContain("Resolvido");
    }

    @Test
    void incidentsExposeTheAssociatedAlertsCount() throws Exception {
        Scene scene = newScene("dash-inc-link", "Dash Inc Link Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();
        UUID owner = ownerUserId(scene);

        Incident linked = seedIncident(
                scene.serviceId(), "Com dois alertas", AlertSeverity.SEV2, FROM_24H.plusSeconds(100), owner);
        seedIncident(
                scene.serviceId(), "Sem alerta", AlertSeverity.SEV3, FROM_24H.plusSeconds(110), owner);
        Alert first = seedAlert(scene.serviceId(), AlertSeverity.SEV2, FROM_24H.plusSeconds(120));
        Alert second = seedAlert(scene.serviceId(), AlertSeverity.SEV3, FROM_24H.plusSeconds(130));

        linkAlert(
                bearer, organizationId, projectId, scene.serviceId(),
                linked.getId().toString(), first.getId().toString());
        linkAlert(
                bearer, organizationId, projectId, scene.serviceId(),
                linked.getId().toString(), second.getId().toString());

        Map<String, Object> incidents = section(dashboardJson(bearer, dashboardUrl(scene)), "incidents");
        assertThat(num(incidents, "activeTotal")).isEqualTo(2);

        List<Map<String, Object>> top = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "incidents", "activeIncidents");
        assertThat(top.get(0).get("title")).isEqualTo("Com dois alertas");
        assertThat(top.get(0).get("associatedAlertsCount")).isEqualTo(2);
        assertThat(top.get(0).get("serviceName")).isEqualTo("Serviço Monitor");
        assertThat(top.get(1).get("associatedAlertsCount")).isEqualTo(0);
    }

    // --- serviços pedindo atenção ---------------------------------------------------

    @Test
    void attentionListsDegradedUnavailableAlertAndIncidentServicesOnly() throws Exception {
        Scene scene = newScene("dash-attn", "Dash Attn Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();
        UUID owner = ownerUserId(scene);

        String degraded = createService(bearer, organizationId, projectId, "Servico Degradado");
        String withAlert = createService(bearer, organizationId, projectId, "Servico Com Alerta");
        String withIncident = createService(bearer, organizationId, projectId, "Servico Com Incidente");
        String healthy = createService(bearer, organizationId, projectId, "Servico Saudavel");
        String ignored = createService(bearer, organizationId, projectId, "Servico Ignorado");

        forceServiceStatus(scene.serviceId(), "UNAVAILABLE", Instant.parse("2026-03-01T10:00:00Z"));
        forceServiceStatus(degraded, "DEGRADED", Instant.parse("2026-03-01T10:05:00Z"));
        forceServiceStatus(healthy, "OPERATIONAL", Instant.parse("2026-03-01T10:10:00Z"));
        forceServiceStatus(ignored, "UNKNOWN", Instant.parse("2026-03-01T10:15:00Z"));
        seedAlert(withAlert, AlertSeverity.SEV3, FROM_24H.plusSeconds(50));
        seedIncident(
                withIncident, "Incidente ativo", AlertSeverity.SEV2, FROM_24H.plusSeconds(60), owner);

        List<Map<String, Object>> attention = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "servicesNeedingAttention",
                "servicesNeedingAttention");
        assertThat(attention)
                .extracting(row -> row.get("serviceName"))
                .containsExactlyInAnyOrder(
                        "Serviço Monitor", "Servico Degradado", "Servico Com Alerta",
                        "Servico Com Incidente")
                .doesNotContain("Servico Saudavel", "Servico Ignorado");

        Map<String, Object> broken = attention.stream()
                .filter(row -> row.get("serviceName").equals("Serviço Monitor"))
                .findFirst()
                .orElseThrow();
        assertThat(broken.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(broken.get("highestSeverity")).isNull();
        assertThat(broken.get("hasActiveIncident")).isEqualTo(false);
        assertThat(broken.get("statusChangedAt")).isEqualTo("2026-03-01T10:00:00Z");

        Map<String, Object> alerted = attention.stream()
                .filter(row -> row.get("serviceName").equals("Servico Com Alerta"))
                .findFirst()
                .orElseThrow();
        assertThat(alerted.get("status")).isEqualTo("UNKNOWN");
        assertThat(alerted.get("highestSeverity")).isEqualTo("SEV3");
        assertThat(alerted.get("hasActiveIncident")).isEqualTo(false);

        Map<String, Object> incidented = attention.stream()
                .filter(row -> row.get("serviceName").equals("Servico Com Incidente"))
                .findFirst()
                .orElseThrow();
        assertThat(incidented.get("highestSeverity")).isEqualTo("SEV2");
        assertThat(incidented.get("hasActiveIncident")).isEqualTo(true);
    }

    @Test
    void attentionOrdersByStatusThenSeverityThenName() throws Exception {
        Scene scene = newScene("dash-attn-order", "Dash Attn Order Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();
        UUID owner = ownerUserId(scene);

        String zulu = createService(bearer, organizationId, projectId, "Zulu");
        String bravo = createService(bearer, organizationId, projectId, "Bravo");
        String alpha = createService(bearer, organizationId, projectId, "Alpha");
        String mike = createService(bearer, organizationId, projectId, "Mike");

        forceServiceStatus(zulu, "UNAVAILABLE", Instant.parse("2026-03-01T07:00:00Z"));
        forceServiceStatus(bravo, "DEGRADED", Instant.parse("2026-03-01T07:05:00Z"));
        forceServiceStatus(alpha, "DEGRADED", Instant.parse("2026-03-01T07:10:00Z"));
        forceServiceStatus(mike, "OPERATIONAL", Instant.parse("2026-03-01T07:15:00Z"));
        seedIncident(bravo, "Sev1 de Bravo", AlertSeverity.SEV1, FROM_24H.plusSeconds(10), owner);
        seedAlert(mike, AlertSeverity.SEV1, FROM_24H.plusSeconds(20));

        // estado (UNAVAILABLE → DEGRADED → demais) → severidade → nome
        List<Map<String, Object>> attention = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "servicesNeedingAttention",
                "servicesNeedingAttention");
        assertThat(attention)
                .extracting(row -> row.get("serviceName"))
                .containsExactly("Zulu", "Bravo", "Alpha", "Mike");
    }

    @Test
    void attentionIsCappedAtTwentyServicesByTheDatabase() throws Exception {
        Scene scene = newScene("dash-attn-cap", "Dash Attn Cap Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        for (int i = 1; i <= 25; i++) {
            String serviceId = createService(
                    bearer, organizationId, projectId, String.format("Svc %02d", i));
            forceServiceStatus(serviceId, "UNAVAILABLE", Instant.parse("2026-03-01T11:00:00Z"));
        }

        List<Map<String, Object>> attention = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "servicesNeedingAttention",
                "servicesNeedingAttention");
        assertThat(attention).hasSize(20);
        assertThat(attention.get(0).get("serviceName")).isEqualTo("Svc 01");
        assertThat(attention.get(19).get("serviceName")).isEqualTo("Svc 20");
    }

    // --- atividade recente ----------------------------------------------------------

    @Test
    void activityCoversEveryTypeNewestFirst() throws Exception {
        Scene scene = newScene("dash-activity", "Dash Activity Corp");
        String bearer = scene.org().ownerBearer();
        UUID owner = ownerUserId(scene);

        Instant fired = FROM_24H.plusSeconds(3600);
        Instant alertResolved = FROM_24H.plusSeconds(7200);
        Instant incidentCreated = FROM_24H.plusSeconds(10800);
        Instant statusChanged = FROM_24H.plusSeconds(14400);
        Instant secondCreated = FROM_24H.plusSeconds(18000);
        Instant linked = FROM_24H.plusSeconds(21600);
        Instant incidentResolved = FROM_24H.plusSeconds(25200);

        seedResolvedAlert(
                scene.serviceId(), AlertSeverity.SEV3, fired, alertResolved);

        Incident first = seedIncident(
                scene.serviceId(), "Primeiro incidente", AlertSeverity.SEV2, incidentCreated, owner);
        saveEvent(
                first, IncidentEventType.STATUS_CHANGED, "Status changed from OPEN to INVESTIGATING",
                owner, statusChanged);
        Incident second = seedIncident(
                scene.serviceId(), "Segundo incidente", AlertSeverity.SEV1, secondCreated, owner);
        saveEvent(second, IncidentEventType.ALERT_LINKED, "Alert linked", owner, linked);
        first.transitionTo(IncidentStatus.RESOLVED, incidentResolved);
        incidentRepository.save(first);

        List<Map<String, Object>> activity = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "recentActivity", "recentActivity");
        assertThat(activity).hasSize(7);
        assertThat(activity).extracting(row -> row.get("type"))
                .containsExactly(
                        "INCIDENT_RESOLVED", "INCIDENT_LINKED", "INCIDENT_CREATED",
                        "INCIDENT_STATUS_CHANGED", "INCIDENT_CREATED", "ALERT_RESOLVED",
                        "ALERT_FIRED");
        assertThat(activity).extracting(row -> row.get("timestamp"))
                .containsExactly(
                        incidentResolved.toString(), linked.toString(), secondCreated.toString(),
                        statusChanged.toString(), incidentCreated.toString(), alertResolved.toString(),
                        fired.toString());
        assertThat(activity.get(0).get("resourceType")).isEqualTo("INCIDENT");
        assertThat(activity.get(0).get("resourceId")).isEqualTo(first.getId().toString());
        assertThat(activity.get(6).get("resourceType")).isEqualTo("ALERT");
        assertThat(activity.get(6).get("title")).isEqualTo("alerta semeado SEV3");
        assertThat(activity.get(6).get("serviceName")).isEqualTo("Serviço Monitor");
    }

    @Test
    void activityRespectsBothWindowBoundaries() throws Exception {
        Scene scene = newScene("dash-activity-window", "Dash Activity Window Corp");
        String bearer = scene.org().ownerBearer();

        seedAlert(scene.serviceId(), AlertSeverity.SEV4, FROM_24H.minusSeconds(1));
        seedAlert(scene.serviceId(), AlertSeverity.SEV4, Instant.parse("2026-03-01T12:30:00Z"));
        seedAlert(scene.serviceId(), AlertSeverity.SEV3, FROM_24H);
        seedAlert(scene.serviceId(), AlertSeverity.SEV3, TO_24H.minusSeconds(1));
        seedResolvedAlert(
                scene.serviceId(), AlertSeverity.SEV4, FROM_24H.minusSeconds(1),
                FROM_24H.plusSeconds(1));

        List<Map<String, Object>> activity = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "recentActivity", "recentActivity");
        assertThat(activity).extracting(row -> row.get("type"))
                .containsExactly("ALERT_FIRED", "ALERT_RESOLVED", "ALERT_FIRED");
        assertThat(activity).extracting(row -> row.get("timestamp"))
                .containsExactly(
                        TO_24H.minusSeconds(1).toString(),
                        FROM_24H.plusSeconds(1).toString(),
                        FROM_24H.toString());

        // os alertas em si NÃO são filtrados por janela: contagem é por status
        Map<String, Object> alerts = section(dashboardJson(bearer, dashboardUrl(scene)), "alerts");
        assertThat(num(alerts, "firingTotal")).isEqualTo(4);
    }

    @Test
    void activityIsCappedAtTwentyNewestItems() throws Exception {
        Scene scene = newScene("dash-activity-cap", "Dash Activity Cap Corp");
        String bearer = scene.org().ownerBearer();
        UUID owner = ownerUserId(scene);

        for (int i = 0; i < 25; i++) {
            seedIncident(
                    scene.serviceId(), "Inc " + i, AlertSeverity.SEV3,
                    FROM_24H.plusSeconds(i), owner);
        }

        List<Map<String, Object>> activity = rows(
                dashboardJson(bearer, dashboardUrl(scene)), "recentActivity", "recentActivity");
        assertThat(activity).hasSize(20);
        assertThat(activity.get(0).get("title")).isEqualTo("Inc 24");
        assertThat(activity.get(19).get("title")).isEqualTo("Inc 5");
    }

    // --- invariantes, volume e pais inativos -------------------------------------------

    @Test
    void everyCounterClosesOverARichProject() throws Exception {
        Scene scene = newScene("dash-invariants", "Dash Invariants Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();
        UUID owner = ownerUserId(scene);

        String monitorId = createMonitor(
                bearer, organizationId, projectId, scene.serviceId(), "Principal");
        seedChecks(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 3, FROM_24H);
        String degraded = createService(bearer, organizationId, projectId, "Degradado");
        forceServiceStatus(scene.serviceId(), "OPERATIONAL", Instant.parse("2026-03-01T06:00:00Z"));
        forceServiceStatus(degraded, "DEGRADED", Instant.parse("2026-03-01T06:05:00Z"));

        seedAlert(scene.serviceId(), AlertSeverity.SEV1, FROM_24H.plusSeconds(600));
        seedAlert(degraded, AlertSeverity.SEV2, FROM_24H.plusSeconds(700));
        seedResolvedAlert(
                scene.serviceId(), AlertSeverity.SEV4, FROM_24H.plusSeconds(800),
                FROM_24H.plusSeconds(900));

        seedIncident(scene.serviceId(), "Ativo um", AlertSeverity.SEV3, FROM_24H.plusSeconds(1000), owner);
        Incident second = seedIncident(
                degraded, "Ativo dois", AlertSeverity.SEV2, FROM_24H.plusSeconds(1100), owner);
        second.transitionTo(IncidentStatus.INVESTIGATING, FROM_24H.plusSeconds(1200));
        incidentRepository.save(second);
        Incident done = seedIncident(
                degraded, "Encerrado", AlertSeverity.SEV4, FROM_24H.plusSeconds(1300), owner);
        done.transitionTo(IncidentStatus.RESOLVED, FROM_24H.plusSeconds(1400));
        incidentRepository.save(done);

        Map<String, Object> body = dashboardJson(bearer, dashboardUrl(scene));
        Map<String, Object> health = section(body, "health");
        Map<String, Object> alerts = section(body, "alerts");
        Map<String, Object> incidents = section(body, "incidents");

        assertThat(num(health, "totalServices")).isEqualTo(2);
        assertThat(num(health, "operational") + num(health, "degraded")
                        + num(health, "unavailable") + num(health, "unknown"))
                .isEqualTo(num(health, "totalServices"));

        assertThat(num(alerts, "firingTotal")).isEqualTo(2);
        assertThat(num(alerts, "sev1") + num(alerts, "sev2") + num(alerts, "sev3") + num(alerts, "sev4"))
                .isEqualTo(num(alerts, "firingTotal"));

        assertThat(num(incidents, "activeTotal")).isEqualTo(2);
        assertThat(num(incidents, "open") + num(incidents, "investigating") + num(incidents, "identified"))
                .isEqualTo(num(incidents, "activeTotal"));

        assertThat(body.get("windowTo")).isEqualTo(TO_24H.toString());
        assertThat(body.get("windowFrom")).isEqualTo(FROM_24H.toString());
        assertThat(rows(body, "alerts", "activeAlerts")).hasSizeLessThanOrEqualTo(10);
        assertThat(rows(body, "incidents", "activeIncidents")).hasSizeLessThanOrEqualTo(10);
        assertThat(rows(body, "servicesNeedingAttention", "servicesNeedingAttention"))
                .hasSizeLessThanOrEqualTo(20);
        assertThat(rows(body, "recentActivity", "recentActivity")).hasSizeLessThanOrEqualTo(20);

        // a disponibilidade do projeto é a dos checks SEMEADOS (3 de 3)
        String raw = dashboardBody(bearer, dashboardUrl(scene));
        assertThat(raw)
                .contains("\"totalChecks\":3", "\"relevantChecks\":3")
                .contains("\"availabilityPercent\":100.00");
    }

    @Test
    void fiftyServicesStillReturnAConsistentDashboard() throws Exception {
        Scene scene = newScene("dash-volume", "Dash Volume Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        for (int i = 1; i <= 50; i++) {
            String serviceId = createService(
                    bearer, organizationId, projectId, String.format("Volume %02d", i));
            if (i % 2 == 0) {
                forceServiceStatus(serviceId, "UNAVAILABLE", Instant.parse("2026-03-01T05:00:00Z"));
            }
        }

        Map<String, Object> body = dashboardJson(bearer, dashboardUrl(scene));
        Map<String, Object> health = section(body, "health");
        assertThat(num(health, "totalServices")).isEqualTo(51);
        assertThat(num(health, "unavailable")).isEqualTo(25);
        assertThat(num(health, "operational") + num(health, "degraded")
                        + num(health, "unavailable") + num(health, "unknown"))
                .isEqualTo(51);
        assertThat(rows(body, "servicesNeedingAttention", "servicesNeedingAttention"))
                .hasSize(20);
    }

    @Test
    void inactiveParentsRemainReadable() throws Exception {
        Scene scene = newScene("dash-inactive", "Dash Inactive Corp");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        String monitorId = createMonitor(
                bearer, organizationId, projectId, scene.serviceId(), "Desligado");
        seedChecks(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 2, FROM_24H);
        seedAlert(scene.serviceId(), AlertSeverity.SEV2, FROM_24H.plusSeconds(60));

        // monitor desligado: os checks e o alerta seguem contando
        changeMonitorActivation(bearer, organizationId, projectId, scene.serviceId(), monitorId, false);

        mockMvc.perform(dashboardGet(bearer, dashboardUrl(scene)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.totalChecks").value(2))
                .andExpect(jsonPath("$.health.totalServices").value(1))
                .andExpect(jsonPath("$.alerts.firingTotal").value(1));

        // serviço desligado: o painel continua legível e a desativação
        // congela o alerta aberto (Prompt 13) — a leitura não muda de forma
        changeActivation(bearer, organizationId, projectId, scene.serviceId(), false);

        mockMvc.perform(dashboardGet(bearer, dashboardUrl(scene)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.totalChecks").value(2))
                .andExpect(jsonPath("$.health.totalServices").value(1))
                .andExpect(jsonPath("$.alerts.firingTotal").value(0));
    }

    // --- isolamento --------------------------------------------------------------------

    @Test
    void panelIsIsolatedByProjectAndTenant() throws Exception {
        Scene alpha = newScene("dash-iso-alpha", "Dash Iso Alpha Corp");
        Scene beta = newScene("dash-iso-beta", "Dash Iso Beta Corp");
        UUID owner = ownerUserId(beta);

        seedAlert(beta.serviceId(), AlertSeverity.SEV1, FROM_24H.plusSeconds(5));
        seedIncident(beta.serviceId(), "Só do Beta", AlertSeverity.SEV1, FROM_24H.plusSeconds(6), owner);

        Map<String, Object> betaPanel = dashboardJson(
                beta.org().ownerBearer(), dashboardUrl(beta));
        assertThat(num(section(betaPanel, "alerts"), "firingTotal")).isEqualTo(1);
        assertThat(num(section(betaPanel, "incidents"), "activeTotal")).isEqualTo(1);
        assertThat(rows(betaPanel, "servicesNeedingAttention", "servicesNeedingAttention"))
                .hasSize(1);

        Map<String, Object> alphaPanel = dashboardJson(
                alpha.org().ownerBearer(), dashboardUrl(alpha));
        assertThat(num(section(alphaPanel, "alerts"), "firingTotal")).isEqualTo(0);
        assertThat(num(section(alphaPanel, "incidents"), "activeTotal")).isEqualTo(0);
        assertThat(rows(alphaPanel, "servicesNeedingAttention", "servicesNeedingAttention"))
                .isEmpty();
        assertThat(rows(alphaPanel, "recentActivity", "recentActivity")).isEmpty();

        // o dono do projeto ALPHA não enxerga o painel do BETA
        mockMvc.perform(dashboardGet(alpha.org().ownerBearer(), dashboardUrl(beta)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- contrato auxiliar ------------------------------------------------------------

    @Test
    void blankRangeFallsBackToTheDefaultTwentyFourHoursWindow() throws Exception {
        Scene scene = newScene("dash-default-range", "Dash Default Range Corp");
        String bearer = scene.org().ownerBearer();

        Map<String, Object> explicit = dashboardJson(bearer, dashboardUrl(scene) + "?range=24h");
        Map<String, Object> blank = dashboardJson(bearer, dashboardUrl(scene) + "?range=");
        Map<String, Object> absent = dashboardJson(bearer, dashboardUrl(scene));

        assertThat(blank.get("rangeKey")).isEqualTo("24h");
        assertThat(blank.get("windowFrom")).isEqualTo(explicit.get("windowFrom"));
        assertThat(blank.get("windowTo")).isEqualTo(explicit.get("windowTo"));
        assertThat(absent.get("rangeKey")).isEqualTo("24h");
        assertThat(absent.get("windowFrom")).isEqualTo(FROM_24H.toString());
    }

    @Test
    void listsAreAlwaysArraysEvenWhenEmpty() throws Exception {
        Fixture org = newOrganization(uniqueEmail("dash-arrays"), "Dash Arrays Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Sem listas");

        Map<String, Object> body = dashboardJson(
                org.ownerBearer(), dashboardUrl(org.organizationId(), projectId));
        assertThat(body.get("servicesNeedingAttention")).isInstanceOf(List.class);
        assertThat(body.get("recentActivity")).isInstanceOf(List.class);
        assertThat(section(body, "alerts").get("activeAlerts")).isInstanceOf(List.class);
        assertThat(section(body, "incidents").get("activeIncidents")).isInstanceOf(List.class);
        assertThat((List<?>) section(body, "alerts").get("activeAlerts")).isEmpty();
        assertThat((List<?>) body.get("recentActivity")).isEmpty();
    }
}
