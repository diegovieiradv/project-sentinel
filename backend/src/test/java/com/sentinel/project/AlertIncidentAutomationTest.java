package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.project.alert.automation.AlertIncidentAutomation;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.incident.model.IncidentStatus;
import com.sentinel.project.incident.service.IncidentService;
import com.sentinel.project.model.Service;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Automação Alert → Incident (Prompt 15) — o contrato de ponta a ponta da
 * flag {@code autoCreateIncident} contra contexto real: o nascimento do
 * alerta, a reconciliação de pós-avaliação, a correlação por serviço e as
 * travas de elegibilidade.
 *
 * <ul>
 *   <li>flag ligada: o alerta NASCE e, após o commit, vira incidente
 *       {@code ALERT_AUTOMATION} com ator nulo, título {@code [AUTO]} e
 *       timeline {@code CREATED + ALERT_LINKED}; ciclos extras não duplicam
 *       nada (idempotência).</li>
 *   <li>flag desligada: alerta dispara, nada nasce — nem depois de ciclos
 *       extras (a reconciliação respeita a opt-in).</li>
 *   <li>correlação: DUAS regras opt-in do mesmo serviço compartilham UM
 *       incidente aberto; {@code RESOLVED} nunca é reutilizado (o próximo
 *       disparo nasce um novo); incidente MANUAL nunca é adotado.</li>
 *   <li>reconciliação: ligar a flag DEPOIS do disparo faz o próximo ciclo
 *       fechar o caso; desligar depois do vínculo não desfaz nada.</li>
 *   <li>elegibilidade: serviço/projeto inativos bloqueiam a escrita (gate
 *       conferido na própria automação); alerta já vinculado é no-op; o
 *       índice parcial da V12 barra o segundo incidente automático aberto e
 *       deixa os manuais coexistirem.</li>
 *   <li>API/RBAC: a flag entra no create/patch/response como qualquer outro
 *       campo de configuração — ausente vira {@code false}, tipo errado é
 *       400, VIEWER continua 403.</li>
 * </ul>
 *
 * <p><b>Relógio:</b> importa o {@link FixedMetricsClockConfig} — o engine só
 * dispara dentro da janela congelada, e as cenas semeiam dentro dela.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertIncidentAutomationTest extends AbstractIncidentIntegrationTest {

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

    @Autowired
    private AlertIncidentAutomation automation;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // --- cenário -------------------------------------------------------------

    /** Cena com monitor + regra + janela semeada, pronta para avaliar. */
    private record AutoScene(Scene scene, String monitorId, String ruleId) {
    }

    /** Payload de regra opt-in (o resto é o service-level canônico). */
    private Map<String, Object> flaggedRule(String name) {
        Map<String, Object> body = serviceLevelRule(name);
        body.put("autoCreateIncident", true);
        return body;
    }

    private AutoScene sceneWithRule(String prefix, Map<String, Object> ruleBody)
            throws Exception {
        Scene scene = newScene(prefix, "Org Automation " + prefix);
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor " + prefix);
        String ruleId = createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                ruleBody);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);
        return new AutoScene(scene, monitorId, ruleId);
    }

    private AutoScene flaggedScene(String prefix) throws Exception {
        return sceneWithRule(prefix, flaggedRule("Regra " + prefix));
    }

    // --- flag ligada: o nascimento automatiza --------------------------------

    @Test
    void flagOn_birthCreatesAutoIncidentLinkedAndOnTimeline() throws Exception {
        AutoScene auto = flaggedScene("auto-birth");
        Scene scene = auto.scene();
        evaluate(scene.serviceId());

        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents).as("o nascimento cria EXATAMENTE um incidente").hasSize(1);
        Incident incident = incidents.get(0);

        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(incident.getCreatedByUserId())
                .as("automação não tem ator humano")
                .isNull();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSeverity())
                .as("severidade é o snapshot do alerta (regra SEV3)")
                .isEqualTo(alert.getSeverity());
        assertThat(incident.getTitle())
                .isEqualTo("[AUTO] Regra auto-birth \u2014 SEV3");
        assertThat(incident.getDescription())
                .as("descrição é a MESMA mensagem do alerta")
                .isEqualTo(alert.getMessage());
        assertThat(incident.getStartedAt())
                .as("quando começou é o alerta que diz")
                .isEqualTo(alert.getStartedAt());

        assertThat(alert.getIncident().getId())
                .as("o alerta aponta para o incidente (coluna da V11)")
                .isEqualTo(incident.getId());

        String incidentId = incident.getId().toString();
        assertThat(eventTypes(incidentId))
                .as("nasce explicado: CREATED e ALERT_LINKED, nesta ordem")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
        List<IncidentEvent> events = eventsOf(incidentId);
        assertThat(events)
                .as("nenhuma linha tem ator — não houve humano")
                .allMatch(event -> event.getActorUserId() == null);
        assertThat(events.get(1).getMessage())
                .as("a linha de vínculo nomeia o alerta")
                .contains(alert.getId().toString());

        // a API diz a mesma coisa que a entidade: origem automática é
        // resposta, e ator nulo é ABSENTE no payload (não uma string "null")
        mockMvc.perform(get(incidentUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ALERT_AUTOMATION"))
                .andExpect(jsonPath("$.createdByUserId").doesNotExist());

        // idempotência: ciclos extras não duplicam incidente nem timeline
        evaluate(scene.serviceId());
        evaluate(scene.serviceId());
        assertThat(incidentsOf(scene.serviceId())).hasSize(1);
        assertThat(eventTypes(incidentId))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }

    // --- flag desligada: nada nasce ------------------------------------------

    @Test
    void flagOff_alertFiresButNothingIsBorn() throws Exception {
        AutoScene off = sceneWithRule("auto-off", serviceLevelRule("Regra auto-off"));
        Scene scene = off.scene();

        evaluate(scene.serviceId());
        assertThat(openAlertsOf(scene.serviceId()))
                .as("o alerta dispara normalmente — a flag não muda o engine")
                .hasSize(1);
        assertThat(incidentsOf(scene.serviceId())).isEmpty();

        // nem a reconciliação dos ciclos seguintes pega carona
        evaluate(scene.serviceId());
        assertThat(incidentsOf(scene.serviceId())).isEmpty();
    }

    // --- correlação ----------------------------------------------------------

    @Test
    void twoOptInRulesShareOneOpenAutoIncident() throws Exception {
        Scene scene = newScene("auto-corr", "Org Automation auto-corr");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor auto-corr");
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                flaggedRule("Regra corr A"));
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                flaggedRule("Regra corr B"));
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 10);

        evaluate(scene.serviceId());

        List<Alert> open = openAlertsOf(scene.serviceId());
        assertThat(open).as("duas regras opt-in, dois alertas").hasSize(2);

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents)
                .as("correlação: UM incidente automático aberto por serviço")
                .hasSize(1);
        Incident incident = incidents.get(0);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(open)
                .as("os DOIS alertas apontam para o mesmo incidente")
                .allMatch(alert -> alert.getIncident().getId().equals(incident.getId()));

        assertThat(eventTypes(incident.getId().toString()))
                .as("um CREATED + um ALERT_LINKED por alerta vinculado")
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.ALERT_LINKED,
                        IncidentEventType.ALERT_LINKED);
    }

    @Test
    void resolvedAutoIncidentIsNotReused_nextDisparityOpensNew() throws Exception {
        AutoScene first = flaggedScene("auto-resolve");
        Scene scene = first.scene();
        String bearer = scene.org().ownerBearer();

        evaluate(scene.serviceId());
        Incident incident1 = incidentsOf(scene.serviceId()).get(0);
        Alert alert1 = singleAlertOf(scene.serviceId());

        changeIncidentStatus(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                incident1.getId().toString(), "RESOLVED");

        // um NOVO disparo (outra regra opt-in) não pode reabrir o RESOLVED
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                flaggedRule("Regra auto-resolve dois"));
        evaluate(scene.serviceId());

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents).hasSize(2);
        Incident resolved = incidentOf(scene.serviceId(), incident1.getId().toString());
        Incident fresh = incidents.stream()
                .filter(i -> !i.getId().equals(incident1.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(resolved.getStatus())
                .as("RESOLVED é terminal — a automação nunca reabre")
                .isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(fresh.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(fresh.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(fresh.getId()).isNotEqualTo(incident1.getId());

        Alert alert2 = openAlertsOf(scene.serviceId()).stream()
                .filter(a -> !a.getId().equals(alert1.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(alert2.getIncident().getId()).isEqualTo(fresh.getId());
        assertThat(alert1.getIncident().getId())
                .as("o alerta antigo continua no incidente antigo")
                .isEqualTo(incident1.getId());
        assertThat(eventTypes(fresh.getId().toString()))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }

    @Test
    void manualIncidentIsNeverAdopted() throws Exception {
        AutoScene auto = flaggedScene("auto-manual");
        Scene scene = auto.scene();
        String bearer = scene.org().ownerBearer();

        String manualId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente aberto à mão");
        evaluate(scene.serviceId());

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents).as("manual convive com o automático, sem se misturar").hasSize(2);

        Incident manual = incidentOf(scene.serviceId(), manualId);
        assertThat(manual.getSource()).isEqualTo(IncidentSource.MANUAL);
        assertThat(manual.getCreatedByUserId()).isNotNull();
        assertThat(eventTypes(manualId))
                .as("a automação não escreveu NADA na timeline manual")
                .containsExactly(IncidentEventType.CREATED);

        Incident autoIncident = incidents.stream()
                .filter(i -> !i.getId().toString().equals(manualId))
                .findFirst()
                .orElseThrow();
        assertThat(autoIncident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(singleAlertOf(scene.serviceId()).getIncident().getId())
                .as("o alerta foi pro automático, não pro manual")
                .isEqualTo(autoIncident.getId());
    }

    // --- reconciliação (flag muda depois do disparo) -------------------------

    @Test
    void flagTurnedOnAfterFiringIsReconciledNextCycle() throws Exception {
        AutoScene plain = sceneWithRule("auto-rec", serviceLevelRule("Regra auto-rec"));
        Scene scene = plain.scene();
        String bearer = scene.org().ownerBearer();

        evaluate(scene.serviceId());
        assertThat(openAlertsOf(scene.serviceId())).hasSize(1);
        assertThat(incidentsOf(scene.serviceId()))
                .as("nasceu com a flag desligada — nada de automação")
                .isEmpty();

        patchRule(scene, plain.ruleId(), Map.of("autoCreateIncident", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));

        evaluate(scene.serviceId());

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents).as("a reconciliação fechou o caso pendente").hasSize(1);
        Incident incident = incidents.get(0);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(singleAlertOf(scene.serviceId()).getIncident().getId())
                .isEqualTo(incident.getId());
        assertThat(eventTypes(incident.getId().toString()))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }

    @Test
    void flagTurnedOffAfterLinkingChangesNothing() throws Exception {
        AutoScene auto = flaggedScene("auto-off-after");
        Scene scene = auto.scene();
        String bearer = scene.org().ownerBearer();

        evaluate(scene.serviceId());
        Incident incident = incidentsOf(scene.serviceId()).get(0);
        String incidentId = incident.getId().toString();

        patchRule(scene, auto.ruleId(), Map.of("autoCreateIncident", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(false));

        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        assertThat(incidentsOf(scene.serviceId()))
                .as("desligar a flag não desfaz nem cria nada")
                .hasSize(1);
        assertThat(incidentOf(scene.serviceId(), incidentId).getStatus())
                .isEqualTo(IncidentStatus.OPEN);
        assertThat(singleAlertOf(scene.serviceId()).getIncident().getId())
                .as("o vínculo existente permanece")
                .isEqualTo(incident.getId());
        assertThat(eventTypes(incidentId))
                .as("timeline intocada")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }

    @Test
    void alreadyManuallyLinkedAlertIsNoOp() throws Exception {
        AutoScene plain = sceneWithRule("auto-noop", serviceLevelRule("Regra auto-noop"));
        Scene scene = plain.scene();
        String bearer = scene.org().ownerBearer();

        evaluate(scene.serviceId());
        String alertId = singleAlertOf(scene.serviceId()).getId().toString();

        String manualId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Vínculo manual antes da flag");
        mockMvc.perform(putCsrf(incidentAlertUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), manualId, alertId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        patchRule(scene, plain.ruleId(), Map.of("autoCreateIncident", true))
                .andExpect(status().isOk());
        evaluate(scene.serviceId());

        assertThat(incidentsOf(scene.serviceId()))
                .as("alerta já vinculado: a automação não nasce outro incidente")
                .hasSize(1);
        Incident manual = incidentOf(scene.serviceId(), manualId);
        assertThat(manual.getSource()).isEqualTo(IncidentSource.MANUAL);
        assertThat(eventTypes(manualId))
                .as("e não escreve na timeline do incidente manual")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
        assertThat(singleAlertOf(scene.serviceId()).getIncident().getId())
                .isEqualTo(manual.getId());
    }

    // --- elegibilidade: pais ativos ------------------------------------------

    /**
     * O gate de pais ativos É a rechecagem de uma corrida: a desativação de
     * serviço/projeto resolve os alertas abertos ({@code PARENT_INACTIVE},
     * contrato do Prompt 13), então o estado "alerta FIRING + pai inativo"
     * só existe na janela em que a desativação e a automação disputam a
     * mesma linha. O teste recria essa janela devolvendo o alerta a
     * {@code FIRING} por SQL (mesmo arranjo de escrita de teste do
     * {@code forceServiceStatus}) e prova que a rechecagem DENTRO de
     * {@code automate} bloqueia — e que, com os dois pais ativos, o gate era
     * o único bloqueio.
     */
    @Test
    void inactiveParentsGateTheAutomation() throws Exception {
        AutoScene plain = sceneWithRule("auto-gate", serviceLevelRule("Regra auto-gate"));
        Scene scene = plain.scene();
        String bearer = scene.org().ownerBearer();

        evaluate(scene.serviceId());
        String alertId = singleAlertOf(scene.serviceId()).getId().toString();
        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID alertUuid = UUID.fromString(alertId);

        patchRule(scene, plain.ruleId(), Map.of("autoCreateIncident", true))
                .andExpect(status().isOk());

        // serviço inativo (a desativação resolveu o alerta; restauramos a
        // janela de corrida) → a rechecagem bloqueia
        changeActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);
        restoreFiring(alertId);
        automation.automate(serviceUuid, alertUuid);
        assertThat(incidentsOf(scene.serviceId()))
                .as("serviço inativo: nada nasce em estado congelado")
                .isEmpty();

        // projeto inativo, serviço de volta → idem
        changeActivation(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), true);
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), false);
        restoreFiring(alertId);
        automation.automate(serviceUuid, alertUuid);
        assertThat(incidentsOf(scene.serviceId()))
                .as("projeto inativo: idem")
                .isEmpty();

        // dois ativos → o gate era o único bloqueio (restore é no-op se o
        // alerta já está FIRING; nenhuma rota de ativação mexe nele)
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), true);
        restoreFiring(alertId);
        automation.automate(serviceUuid, alertUuid);
        assertThat(incidentsOf(scene.serviceId()))
                .as("com os dois pais ativos, a automação passa")
                .hasSize(1);
        assertThat(singleAlertOf(scene.serviceId()).getIncident()).isNotNull();
    }

    /**
     * Volta o alerta a {@code FIRING} desfazendo a resolução
     * {@code PARENT_INACTIVE} que a desativação aplicou — escrita de TESTE
     * direta (mesmo arranjo do {@code forceServiceStatus}) para isolar o
     * gate da automação do efeito colateral legítimo do Prompt 13.
     */
    private void restoreFiring(String alertId) {
        jdbcTemplate.update(
                "UPDATE alerts SET status = 'FIRING', resolved_at = NULL,"
                        + " resolution_reason = NULL WHERE id = ?",
                UUID.fromString(alertId));
    }

    // --- trava de correlação (índice parcial da V12) --------------------------

    @Test
    void partialUniqueIndexBlocksSecondOpenAutoIncidentButNotManuals() throws Exception {
        AutoScene auto = flaggedScene("auto-index");
        Scene scene = auto.scene();
        evaluate(scene.serviceId());
        assertThat(incidentsOf(scene.serviceId())).hasSize(1);

        UUID serviceUuid = UUID.fromString(scene.serviceId());
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO incidents
                          (id, service_id, title, severity, status, started_at, source,
                           created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(), serviceUuid, "Segundo automatico", "SEV3", "OPEN",
                        Timestamp.from(NOW), "ALERT_AUTOMATION",
                        Timestamp.from(NOW), Timestamp.from(NOW), 0L))
                .as("segundo AUTO aberto para o MESMO serviço viola "
                        + "uq_incidents_auto_open_per_service")
                .isInstanceOf(DuplicateKeyException.class);

        // manuais ficam fora do predicado — coexistem sem conflito
        jdbcTemplate.update(
                """
                INSERT INTO incidents
                  (id, service_id, title, severity, status, started_at, source,
                   created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), serviceUuid, "Manual convivente", "SEV4", "OPEN",
                Timestamp.from(NOW), "MANUAL",
                Timestamp.from(NOW), Timestamp.from(NOW), 0L);
        assertThat(incidentsOf(scene.serviceId()))
                .as("manual somou, o segundo automático não")
                .hasSize(2);
    }

    /**
     * Regressão de CASE: o status do alerta vive em CAIXA ALTA ({@code FIRING}
     * via {@code @Enumerated(STRING)} + CHECK da V10) — um predicado
     * {@code 'firing'} na V12 criaria um índice que NUNCA casa com nenhuma
     * linha (bug silencioso: a query continua correta, só o índice é inútil).
     */
    @Test
    void v12IndexPredicatesMatchTheStoredStatusCase() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes"
                        + " WHERE tablename IN ('alerts', 'incidents')",
                String.class);

        String reconciliationIndex = definitions.stream()
                .filter(def -> def.contains("idx_alerts_auto_create_firing"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "idx_alerts_auto_create_firing ausente da V12"));
        assertThat(reconciliationIndex)
                .as("predicado no MESMO case armazenado ('FIRING')")
                .contains("'FIRING'")
                .doesNotContain("'firing'");

        String uniqueAutoIndex = definitions.stream()
                .filter(def -> def.contains("uq_incidents_auto_open_per_service"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "uq_incidents_auto_open_per_service ausente da V12"));
        assertThat(uniqueAutoIndex)
                .as("trava restrita a origem automática ainda não RESOLVED")
                .contains("ALERT_AUTOMATION")
                .contains("RESOLVED");
    }

    // --- API da flag ---------------------------------------------------------

    @Test
    void flagRoundTripsThroughCreatePatchAndResponse() throws Exception {
        Scene scene = newScene("auto-api", "Org Automation auto-api");
        String bearer = scene.org().ownerBearer();
        String url = alertRulesUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());

        Map<String, Object> withFlag = flaggedRule("Regra com flag");
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(withFlag)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));

        // chave ausente = false = contrato do Prompt 12 intacto
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("Regra sem a chave"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autoCreateIncident").value(false));

        Map<String, Object> malformed = serviceLevelRule("Regra tipo errado");
        malformed.put("autoCreateIncident", "sim");
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(malformed)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        String ruleId = createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra para patch");

        patchRule(scene, ruleId, Map.of("autoCreateIncident", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));

        // null explícito = ausente = não altera (mesmo contrato do monitorId)
        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("autoCreateIncident", null);
        patchRule(scene, ruleId, keep)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));

        patchRule(scene, ruleId, Map.of("autoCreateIncident", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(false));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).isAutoCreateIncident()).isFalse();
    }

    @Test
    void flagDoesNotBypassRbac() throws Exception {
        Scene scene = newScene("auto-rbac", "Org Automation auto-rbac");
        String owner = scene.org().ownerBearer();

        String viewerEmail = uniqueEmail("auto-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(owner, scene.org().organizationId(), viewerEmail, "VIEWER");
        String operatorEmail = uniqueEmail("auto-operator");
        String operator = registerAndLogin("Operator Otto", operatorEmail);
        addMember(owner, scene.org().organizationId(), operatorEmail, "OPERATOR");

        String url = alertRulesUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // VIEWER não cria regra nem com a flag — a automação não é atalho
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(flaggedRule("Regra do viewer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String ruleId = createAlertRule(
                owner, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Regra rbac da flag");

        patchRuleWith(scene, ruleId, Map.of("autoCreateIncident", true), viewer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).isAutoCreateIncident())
                .as("403 não escreve")
                .isFalse();

        // OPERATOR é papel de mutação — a flag é configuração como qualquer
        // outra, sem regra própria de permissão
        patchRuleWith(scene, ruleId, Map.of("autoCreateIncident", true), operator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));
        assertThat(alertRuleOf(scene.serviceId(), ruleId).isAutoCreateIncident()).isTrue();
    }

    // --- revisões: semântica, transação, escopo, validação -------------------

    /**
     * Revisão P2-1: {@code Alert RESOLVED ≠ Incident RESOLVED}. A
     * recuperação do alerta (Prompt 13) NÃO resolve o incidente — a
     * automação nunca escreve status (só cria e vincula) — e o próximo
     * disparo REUTILIZA o mesmo incidente automático ainda aberto, em vez
     * de abrir outro.
     */
    @Test
    void alertRecoveryNeverResolvesItsIncident_nextFireRejoinsTheSameOpenIncident()
            throws Exception {
        Scene scene = newScene("auto-recover", "Org Automation auto-recover");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Monitor auto-recover");
        createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                flaggedRule("Regra auto-recover"));
        List<MonitorCheck> checks = new ArrayList<>();
        checks.addAll(seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1));
        checks.addAll(seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1));

        evaluate(scene.serviceId());
        Incident incident = incidentsOf(scene.serviceId()).get(0);
        Alert first = singleAlertOf(scene.serviceId());
        assertThat(first.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(first.getIncident().getId()).isEqualTo(incident.getId());

        // janela saudável → o ALERTA resolve (AUTO); o INCIDENTE não é tocado
        monitorCheckRepository.deleteAll(checks.stream()
                .filter(check -> check.getOutcome() == CheckOutcome.TARGET_FAILURE)
                .toList());
        evaluate(scene.serviceId());

        List<Alert> afterRecovery = alertsOf(scene.serviceId());
        assertThat(afterRecovery).hasSize(1);
        assertThat(afterRecovery.get(0).getStatus())
                .as("a recuperação do Prompt 13 resolve o ALERTA")
                .isEqualTo(AlertStatus.RESOLVED);
        assertThat(incidentOf(scene.serviceId(), incident.getId().toString()).getStatus())
                .as("Alert RESOLVED ≠ Incident RESOLVED: a automação nunca resolve status")
                .isEqualTo(IncidentStatus.OPEN);
        assertThat(eventTypes(incident.getId().toString()))
                .as("timeline intacta pela recuperação")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);

        // cai de novo → NOVO alerta → se junta ao MESMO incidente aberto
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        List<Alert> all = alertsOf(scene.serviceId());
        assertThat(all).hasSize(2);
        Alert second = all.stream()
                .filter(alert -> alert.getStatus() == AlertStatus.FIRING)
                .findFirst()
                .orElseThrow();
        assertThat(second.getIncident().getId())
                .as("novo disparo reutiliza o incidente automático ainda aberto")
                .isEqualTo(incident.getId());
        assertThat(incidentsOf(scene.serviceId()))
                .as("nenhum incidente novo nasceu")
                .hasSize(1);
        assertThat(eventTypes(incident.getId().toString()))
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.ALERT_LINKED,
                        IncidentEventType.ALERT_LINKED);
    }

    /**
     * Revisão P2-2: a escrita da automação vive na SUA transação
     * ({@code REQUIRES_NEW} aplicado pelo {@code TransactionTemplate} do
     * orquestrador) — um chamador transacional que DESISTE depois
     * ({@code setRollbackOnly}) não desfaz o incidente. Com
     * {@code REQUIRED} (o bug que esta revisão pegou), o incidente nasceria
     * dentro da transação alheia e sumiria aqui.
     */
    @Test
    void automationWriteSurvivesTheCallerTransactionRollback() throws Exception {
        AutoScene plain = sceneWithRule("auto-tx", serviceLevelRule("Regra auto-tx"));
        Scene scene = plain.scene();
        evaluate(scene.serviceId());
        assertThat(openAlertsOf(scene.serviceId())).hasSize(1);
        assertThat(incidentsOf(scene.serviceId())).isEmpty();

        patchRule(scene, plain.ruleId(), Map.of("autoCreateIncident", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));

        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID alertUuid = singleAlertOf(scene.serviceId()).getId();

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            automation.automate(serviceUuid, alertUuid);
            status.setRollbackOnly(); // o chamador desiste DEPOIS da escrita
        });

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents)
                .as("REQUIRES_NEW: o rollback do chamador não alcança a escrita da automação")
                .hasSize(1);
        Incident incident = incidents.get(0);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(singleAlertOf(scene.serviceId()).getIncident().getId())
                .isEqualTo(incident.getId());
        assertThat(eventTypes(incident.getId().toString()))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }

    /**
     * Revisão P2-4: o par {@code (serviceId, alertId)} É a cadeia de dono —
     * o serviço de OUTRO tenant no lugar do serviço dono faz
     * {@code findByServiceIdAndId} devolver vazio e a automação vira no-op
     * silencioso, sem escrever em LUGAR NENHUM (anti-IDOR do caminho interno,
     * mesmo contrato do vínculo manual). O par certo prova que o escopo era
     * o único bloqueio.
     */
    @Test
    void mismatchedServiceIdIsANoOpAndTheRightPairWrites() throws Exception {
        AutoScene plain = sceneWithRule("auto-scope", serviceLevelRule("Regra auto-scope"));
        Scene scene = plain.scene();
        evaluate(scene.serviceId());
        patchRule(scene, plain.ruleId(), Map.of("autoCreateIncident", true))
                .andExpect(status().isOk());

        Scene other = newScene("auto-scope-other", "Org Automation auto-scope-other");
        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID otherUuid = UUID.fromString(other.serviceId());
        UUID alertUuid = singleAlertOf(scene.serviceId()).getId();

        automation.automate(otherUuid, alertUuid);
        assertThat(incidentsOf(scene.serviceId()))
                .as("par errado: nada nasce no serviço do alerta")
                .isEmpty();
        assertThat(incidentsOf(other.serviceId()))
                .as("nada nasce no serviço apontado por engano")
                .isEmpty();
        assertThat(singleAlertOf(scene.serviceId()).getIncident())
                .as("e o alerta segue sem vínculo")
                .isNull();

        automation.automate(serviceUuid, alertUuid);
        assertThat(incidentsOf(scene.serviceId()))
                .as("par certo: o escopo era o único bloqueio")
                .hasSize(1);
        assertThat(incidentsOf(other.serviceId()))
                .as("o outro serviço continua intacto")
                .isEmpty();
        assertThat(singleAlertOf(scene.serviceId()).getIncident()).isNotNull();
    }

    /**
     * Revisão P2-6: {@code createAutomated} não passa pelo {@code @Valid} do
     * controller — a validação de título vive NO service, no MESMO formato
     * do create manual ({@code 400 VALIDATION_ERROR}), e rejeita ANTES de
     * qualquer escrita (o CHECK não-vazio da V11 nunca é a primeira linha de
     * defesa).
     */
    @Test
    void createAutomatedRejectsBlankTitleBeforeAnyWrite() throws Exception {
        AutoScene auto = flaggedScene("auto-valid");
        Scene scene = auto.scene();
        evaluate(scene.serviceId());
        assertThat(incidentsOf(scene.serviceId())).hasSize(1);
        Service service = singleAlertOf(scene.serviceId()).getService();

        assertThatThrownBy(() -> incidentService.createAutomated(
                        service, "   ", null, AlertSeverity.SEV3, NOW))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(api.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessage()).isEqualTo("title: must not be blank");
                });

        assertThat(incidentsOf(scene.serviceId()))
                .as("nenhuma escrita aconteceu antes da validação")
                .hasSize(1);
    }

    // --- apoio ---------------------------------------------------------------

    private ResultActions patchRule(Scene scene, String ruleId, Map<String, Object> body)
            throws Exception {
        return patchRuleWith(scene, ruleId, body, scene.org().ownerBearer());
    }

    private ResultActions patchRuleWith(
            Scene scene, String ruleId, Map<String, Object> body, String bearer)
            throws Exception {
        return mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)));
    }

    /** A regra persistida (estado verdadeiro depois de um PATCH/403). */
    private AlertRule ruleOf(AutoScene auto) {
        return alertRuleOf(auto.scene().serviceId(), auto.ruleId());
    }

    /** Garante que o leitor de timeline do serviço está coerente (smoke). */
    @Test
    void alertRepositoryRemembersTheLinkedIncident() throws Exception {
        AutoScene auto = flaggedScene("auto-readback");
        Scene scene = auto.scene();
        evaluate(scene.serviceId());

        Incident incident = incidentsOf(scene.serviceId()).get(0);
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alertRepository.findByServiceIdAndId(
                        UUID.fromString(scene.serviceId()), alert.getId())
                .orElseThrow()
                .getIncident()
                .getId())
                .isEqualTo(incident.getId());
        assertThat(ruleOf(auto).isAutoCreateIncident()).isTrue();
        mockMvc.perform(get(alertUrl(
                                scene.org().organizationId(), scene.projectId(),
                                scene.serviceId(), alert.getId().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk());
    }
}
