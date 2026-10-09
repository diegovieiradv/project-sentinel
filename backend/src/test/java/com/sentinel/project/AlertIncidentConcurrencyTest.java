package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.project.alert.automation.AlertIncidentAutomation;
import com.sentinel.project.alert.automation.LinkRaceLostException;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.monitor.model.CheckOutcome;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Corridas da automação Alert → Incident (Prompt 15, revisões P1-1 e P1-2) —
 * as duas interleavings que o caminho sequencial nunca alcança, forçadas com
 * portões determinísticos ({@link FlakyAutomationConfig}) e disputadas contra
 * o MESMO Postgres de teste, mais a rede de falhas que o isolamento promete
 * (revisão P2-3):
 *
 * <ul>
 *   <li><b>P1-2 (correlação):</b> dois alertas elegíveis do MESMO serviço
 *       criando ao mesmo tempo — ambos leem "nenhum incidente aberto" ANTES
 *       de qualquer um escrever (o portão fica depois da consulta), o índice
 *       parcial único da V12 derruba o perdedor e o retry em transação nova
 *       converge para UM incidente com os DOIS alertas vinculados, sem
 *       exceção para o caller;</li>
 *   <li><b>P1-1 (vínculo):</b> a criação vence a rechecagem mas o
 *       {@code UPDATE} perde para o vínculo humano — a criação (e a sua
 *       timeline) são revertidas na transação inteira e NENHUM incidente
 *       órfão sobrevive ao commit;</li>
 *   <li><b>P2-3 (falha injetada):</b> a correlação explode no meio do caminho
 *       — nada propaga para o ciclo de avaliação e nada é escrito pela
 *       metade; sem nenhuma intervenção, o ciclo seguinte reconcilia.</li>
 * </ul>
 *
 * <p><b>Determinismo:</b> nenhum teste espera "quem vence" — todos convergem
 * para o MESMO desfecho, que é assegurado pelo banco. Os portões têm teto de
 * 30&nbsp;s (hang é falha, não flakiness) e são liberados no
 * {@code finally}/{@code @AfterEach}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FixedMetricsClockConfig.class, FlakyAutomationConfig.class})
class AlertIncidentConcurrencyTest extends AbstractIncidentIntegrationTest {

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

    @AfterEach
    void releaseGates() {
        FlakyAutomationConfig.reset();
    }

    // --- cenário -------------------------------------------------------------

    /** Cena com monitor + regra + janela em falha, com o id da regra à mão. */
    private record CodedScene(Scene scene, String ruleId) {
    }

    /**
     * Cena com monitor + regra + janela em falha. {@code autoCreateIncident}
     * nasce nas flags do payload — é o que permite ao teste decidir QUANDO a
     * automação pode rodar (a corrida precisa de alertas ainda sem vínculo).
     */
    private CodedScene sceneWithRule(String prefix, Map<String, Object> ruleBody)
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
        return new CodedScene(scene, ruleId);
    }

    /** Payload de regra opt-in (o resto é o service-level canônico). */
    private Map<String, Object> flaggedRule(String name) {
        Map<String, Object> body = serviceLevelRule(name);
        body.put("autoCreateIncident", true);
        return body;
    }

    /** Liga a flag via API (o mesmo PATCH parcial que o dono usa). */
    private void enableFlag(Scene scene, String ruleId) throws Exception {
        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), ruleId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("autoCreateIncident", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateIncident").value(true));
    }

    /**
     * Dispara {@code automate} para CADA alerta em paralelo e devolve as
     * falhas (lista thread-safe) — cada chamada roda na SUA transação
     * {@code REQUIRES_NEW}, exatamente como no ciclo de produção.
     */
    private List<Throwable> automateConcurrently(UUID serviceUuid, List<UUID> alertIds) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(alertIds.size());
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (UUID alertId : alertIds) {
                UUID id = alertId;
                runs.add(pool.submit(() -> {
                    try {
                        automation.automate(serviceUuid, id);
                    }
                    catch (Throwable ex) {
                        failures.add(ex);
                    }
                    return null;
                }));
            }
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("race test interrupted", ex);
        }
        catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("racing automate never finished", ex);
        }
        finally {
            pool.shutdownNow();
        }
        return failures;
    }

    // --- P1-2: dois criadores, um índice ------------------------------------

    /**
     * Revisão P1-2: o pré-check do correlator é uma LEITURA e o índice
     * {@code uq_incidents_auto_open_per_service} é a autoridade — mas o
     * perdedor não pode simplesmente desistir: no Postgres a violação aborta
     * a transação corrente ({@code current transaction is aborted}). Com os
     * dois lados lendo "vazio" ANTES de qualquer criação, a corrida é
     * garantida e o desfecho é o único possível: UM incidente, DOIS alertas,
     * timeline sem linha órfã, zero erro para o caller.
     */
    @Test
    void twoEligibleAlertsRacingTheCorrelationIndexEndInOneIncidentWithBothAlerts()
            throws Exception {
        CodedScene coded = sceneWithRule("auto-race", serviceLevelRule("Regra race A"));
        Scene scene = coded.scene();
        String bearer = scene.org().ownerBearer();
        String ruleB = createAlertRule(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                serviceLevelRule("Regra race B"));

        // nasce com a flag DESLIGADA dos dois lados: nenhum alerta é
        // vinculado antes da disputa
        evaluate(scene.serviceId());
        List<UUID> alertIds = openAlertsOf(scene.serviceId()).stream()
                .map(Alert::getId)
                .toList();
        assertThat(alertIds).as("duas regras, dois alertas abertos").hasSize(2);
        assertThat(incidentsOf(scene.serviceId())).isEmpty();

        enableFlag(scene, coded.ruleId());
        enableFlag(scene, ruleB);
        UUID serviceUuid = UUID.fromString(scene.serviceId());

        // o portão fica DEPOIS da consulta: cada lado enxerga "vazio" antes
        // de o outro criar — a corrida deixa de ser sorte de escalonamento
        FlakyAutomationConfig.correlationGate = new CountDownLatch(2);
        List<Throwable> failures = automateConcurrently(serviceUuid, alertIds);

        assertThat(failures)
                .as("a corrida NÃO vira erro: o perdedor re-tenta e só vincula")
                .isEmpty();

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents)
                .as("índice parcial da V12 + retry em transação nova → UM incidente")
                .hasSize(1);
        Incident incident = incidents.get(0);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(openAlertsOf(scene.serviceId()))
                .as("os DOIS alertas terminam no MESMO incidente")
                .hasSize(2)
                .allMatch(alert -> alert.getIncident().getId().equals(incident.getId()));
        assertThat(eventTypes(incident.getId().toString()))
                .as("CREATED uma vez + um ALERT_LINKED por alerta — nada órfão")
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.ALERT_LINKED,
                        IncidentEventType.ALERT_LINKED);

        // banco consistente com o que a API enxerga
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM incidents"
                                + " WHERE service_id = ? AND source = 'ALERT_AUTOMATION'",
                        Integer.class, serviceUuid))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM incidents"
                                + " WHERE service_id = ? AND source = 'MANUAL'",
                        Integer.class, serviceUuid))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alerts WHERE service_id = ? AND incident_id = ?",
                        Integer.class, serviceUuid, incident.getId()))
                .isEqualTo(2);
    }

    // --- P1-1: criação vence, vínculo perde ---------------------------------

    /**
     * Revisão P1-1: a criação passa pela rechecagem mas o {@code UPDATE}
     * condicional devolve 0 porque o vínculo HUMANO venceu no meio. A
     * criação é revertida junto com a sua timeline (uma única transação) e o
     * incidente manual é o único que sobrevive — nenhum incidente nasce sem
     * alerta apontando para ele, e nenhuma linha de timeline fica órfã.
     */
    @Test
    void manualLinkWinningTheRaceLeavesNoOrphanIncident() throws Exception {
        CodedScene coded = sceneWithRule("auto-orphan", serviceLevelRule("Regra auto-orphan"));
        Scene scene = coded.scene();
        String bearer = scene.org().ownerBearer();
        evaluate(scene.serviceId());
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getIncident()).isNull();

        // a flag liga DEPOIS do disparo: o chamador manual é quem decide
        enableFlag(scene, coded.ruleId());
        String manualId = createIncident(
                bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                "Incidente aberto à mão antes da corrida");

        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID alertUuid = alert.getId();
        FlakyAutomationConfig.linkReached = new CountDownLatch(1);
        FlakyAutomationConfig.linkProceed = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = pool.submit(() -> {
                automation.automate(serviceUuid, alertUuid);
                return null;
            });
            assertThat(FlakyAutomationConfig.linkReached.await(30, TimeUnit.SECONDS))
                    .as("a automação criou o incidente e parou ANTES do UPDATE")
                    .isTrue();

            // o humano vence ENQUANTO a automação está presa antes do UPDATE
            mockMvc.perform(putCsrf(incidentAlertUrl(
                                            scene.org().organizationId(), scene.projectId(),
                                            scene.serviceId(), manualId,
                                            alertUuid.toString()))
                            .header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk());

            FlakyAutomationConfig.linkProceed.countDown();

            assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
                    .as("criação órfã não sobrevive: rollback de criação + timeline")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LinkRaceLostException.class);
        }
        finally {
            FlakyAutomationConfig.linkProceed.countDown();
            pool.shutdownNow();
        }

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents)
                .as("só o incidente manual sobrevive — o órfão foi revertido")
                .hasSize(1);
        assertThat(incidents.get(0).getId().toString()).isEqualTo(manualId);
        assertThat(incidents.get(0).getSource()).isEqualTo(IncidentSource.MANUAL);
        assertThat(eventTypes(manualId))
                .as("a timeline manual tem exatamente o que o humano fez")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);

        Alert after = singleAlertOf(scene.serviceId());
        assertThat(after.getIncident().getId().toString())
                .as("o alerta ficou no incidente do humano")
                .isEqualTo(manualId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM incident_events"
                                + " WHERE incident_id NOT IN (SELECT id FROM incidents)",
                        Integer.class))
                .as("nenhum evento órfão: a tentativa revertida não deixou resto")
                .isZero();
    }

    // --- RES-07: violação de integridade -------------------------------------

    /**
     * RES-07: o orquestrador absorve UMA violação de integridade — e só a
     * da constraint parcial da V12. Injetada a cada tentativa dentro da
     * transação (é lá que o banco a acusaria), o método esgota o TETO de 3
     * e retorna NORMALMENTE: nenhuma exceção chega ao caller e nada fica
     * meio-escrito. É a prova do contrato "best-effort + nunca loop" do
     * Javadoc de {@code automate}.
     */
    @Test
    void benignCorrelationViolationIsRetriedUpToTheCeilingAndNeverPropagates()
            throws Exception {
        CodedScene coded = sceneWithRule("auto-ceiling", serviceLevelRule("Regra ceiling"));
        Scene scene = coded.scene();
        evaluate(scene.serviceId());
        enableFlag(scene, coded.ruleId());
        assertThat(incidentsOf(scene.serviceId()))
                .as("cenário nasce sem incidente: a automação não rodou no evaluate")
                .isEmpty();

        AtomicInteger attempts = new AtomicInteger();
        FlakyAutomationConfig.corruptCorrelation = () -> {
            attempts.incrementAndGet();
            return "could not create unique index \"uq_incidents_auto_open_per_service\""
                    + " on table \"incidents\"";
        };
        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID alertUuid = singleAlertOf(scene.serviceId()).getId();

        // NÃO lança: corrida benigna nunca sobe para o ciclo de avaliação
        automation.automate(serviceUuid, alertUuid);

        assertThat(attempts.get())
                .as("teto de 3 tentativas — uma original e duas novas transações")
                .isEqualTo(3);
        assertThat(incidentsOf(scene.serviceId()))
                .as("tentativa abortada no meio não deixa nada meio-escrito")
                .isEmpty();
        assertThat(singleAlertOf(scene.serviceId()).getIncident())
                .as("sem vínculo: nada foi criado nem associado")
                .isNull();
    }

    /**
     * Recíproco: violação que <b>não</b> é a corrida da V12 (constraint
     * qualquer, NOT NULL, CHECK…) é DEFEITO, não ruído de concorrência.
     * Mesma exceção, mensagem diferente — e o desfecho muda: UMA tentativa,
     * sem retry, e a exceção sobe para o caller logar.
     */
    @Test
    void nonCorrelationIntegrityViolationPropagatesOnTheFirstAttempt() throws Exception {
        CodedScene coded = sceneWithRule("auto-broken", serviceLevelRule("Regra broken"));
        Scene scene = coded.scene();
        evaluate(scene.serviceId());
        enableFlag(scene, coded.ruleId());
        assertThat(incidentsOf(scene.serviceId())).isEmpty();

        AtomicInteger attempts = new AtomicInteger();
        FlakyAutomationConfig.corruptCorrelation = () -> {
            attempts.incrementAndGet();
            return "null value in column \"incident_id\" violates not-null constraint";
        };
        UUID serviceUuid = UUID.fromString(scene.serviceId());
        UUID alertUuid = singleAlertOf(scene.serviceId()).getId();

        assertThatThrownBy(() -> automation.automate(serviceUuid, alertUuid))
                .as("fora da corrida da V12 a violação NÃO é engolida")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts.get())
                .as("nenhum retry para violação que não é a corrida conhecida")
                .isEqualTo(1);
    }

    // --- P2-3: falha injetada ------------------------------------------------

    /**
     * Revisão P2-3: injeta a falha NA correlação (o colaborador real
     * explode) e prova as duas metades do contrato — (a) nada sobe para o
     * ciclo: listener {@code AFTER_COMMIT} e reconciler absorvem, o alerta
     * nasce e segue FIRING sem nenhum incidente pela metade; (b) SEM
     * intervenção, o ciclo seguinte reconcilia e fecha o caso.
     */
    @Test
    void correlationFailureIsIsolatedAndReconcilesOnTheNextCycle() throws Exception {
        CodedScene coded = sceneWithRule("auto-fail", flaggedRule("Regra auto-fail"));
        Scene scene = coded.scene();

        FlakyAutomationConfig.correlatorExplodes = true;
        evaluate(scene.serviceId());

        assertThat(openAlertsOf(scene.serviceId()))
                .as("a falha não desfaz nem segura o nascimento do alerta")
                .hasSize(1);
        assertThat(incidentsOf(scene.serviceId()))
                .as("nada meio-escrito: a correlação falhou ANTES de criar")
                .isEmpty();

        FlakyAutomationConfig.correlatorExplodes = false;
        evaluate(scene.serviceId());

        List<Incident> incidents = incidentsOf(scene.serviceId());
        assertThat(incidents)
                .as("ciclo seguinte reconcilia sozinho — sem humano, sem retry manual")
                .hasSize(1);
        Incident incident = incidents.get(0);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        Alert alert = singleAlertOf(scene.serviceId());
        assertThat(alert.getIncident()).isNotNull();
        assertThat(alert.getIncident().getId()).isEqualTo(incident.getId());
        assertThat(eventTypes(incident.getId().toString()))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.ALERT_LINKED);
    }
}
