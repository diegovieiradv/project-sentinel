package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deduplicação dos alertas (Prompt 13) — critérios 57–61: UM alerta aberto
 * por regra. O segundo ciclo TOCA o existente (última avaliação e valor
 * gatilho) em vez de criar outro; o índice parcial UNIQUE da V10 absorve a
 * corrida; linhas RESOLVED nunca colidem entre si; e a dedup é POR REGRA
 * (regras diferentes convivem abertas).
 *
 * <p>Mais a correção da revisão P2-1: um exercício de PARALELISMO REAL (N
 * threads simultâneas no caminho de evaluate) — o critério 59 prova a
 * constraint no caminho sequencial, este prova o desfecho sob corrida.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertDedupTest extends AbstractAlertEngineIntegrationTest {

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

    // --- cenário repetido: 1 SUCCESS + 1 TARGET_FAILURE = 50% < 99.9 ---------

    private String fireOnce(Scene scene) throws Exception {
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor dedup");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra de dedup");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        return ruleId;
    }

    // --- #57: segundo ciclo não cria outro alerta ---------------------------

    @Test
    void criterio57_segundo_ciclo_mantem_o_mesmo_alerta_aberto() throws Exception {
        Scene scene = newScene("alert-dedup-cycle", "Org AlertEngine DedupCycle");
        fireOnce(scene);
        Instant firstStartedAt = singleAlertOf(scene.serviceId()).getStartedAt();

        evaluate(scene.serviceId());
        evaluate(scene.serviceId());

        assertThat(openAlertsOf(scene.serviceId()))
                .as("três ciclos → exatamente UM alerta aberto")
                .hasSize(1);
        assertThat(singleAlertOf(scene.serviceId()).getStartedAt())
                .as("o alerta original não é recriado")
                .isEqualTo(firstStartedAt);
    }

    // --- #58: toque atualiza avaliação/valor sem mudar início/mensagem -------

    @Test
    void criterio58_reavaliacao_toca_o_alerta_sem_reescrever_inicio_nem_mensagem()
            throws Exception {
        Scene scene = newScene("alert-dedup-touch", "Org AlertEngine DedupTouch");
        fireOnce(scene);
        String monitorId = monitorIdOf(scene);

        Alert before = singleAlertOf(scene.serviceId());
        String messageBefore = before.getMessage();
        Instant startedAtBefore = before.getStartedAt();
        assertThat(before.getTriggerValue()).isEqualByComparingTo("50.00");

        // piora a janela: 2 SUCCESS + 1 TARGET_FAILURE = 66.67% (ainda < 99.9)
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        evaluate(scene.serviceId());

        Alert after = singleAlertOf(scene.serviceId());
        assertThat(openAlertsOf(scene.serviceId())).hasSize(1);
        assertThat(after.getStartedAt())
                .as("dedup preserva o início do alerta")
                .isEqualTo(startedAtBefore);
        assertThat(after.getMessage())
                .as("a mensagem continua sendo a do FIRING original")
                .isEqualTo(messageBefore);
        assertThat(after.getTriggerValue())
                .as("o valor gatilho acompanha a última avaliação")
                .isEqualByComparingTo(new BigDecimal("66.67"));
        assertThat(after.getLastEvaluatedAt()).isEqualTo(NOW);
    }

    // --- #59: índice parcial UNIQUE absorve a corrida ------------------------

    @Test
    void criterio59_indice_parcial_rejeita_segundo_alerta_aberto_para_a_mesma_regra()
            throws Exception {
        Scene scene = newScene("alert-dedup-race", "Org AlertEngine DedupRace");
        String ruleId = fireOnce(scene);

        // corrida simulada: inserir OUTRO alerta FIRING para a MESMA regra,
        // direto no repositório, como faria um evaluator concorrente
        var service = serviceRepository.findById(java.util.UUID.fromString(scene.serviceId()))
                .orElseThrow();
        var rule = alertRuleOf(scene.serviceId(), ruleId);

        DataIntegrityViolationException race = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> alertRepository.saveAndFlush(new Alert(
                        service, rule, "alerta concorrente", new BigDecimal("50"), NOW)));

        assertThat(race)
                .as("uq_alerts_open_rule (parcial, WHERE FIRING) derruba a duplicata")
                .isNotNull();
        assertThat(openAlertsOf(scene.serviceId())).hasSize(1);
    }

    // --- #60: RESOLVED nunca colide — re-disparo é UM NOVO alerta ------------

    @Test
    void criterio60_apos_resolucao_o_redisparo_abre_um_novo_alerta_sem_colidir()
            throws Exception {
        Scene scene = newScene("alert-dedup-rescan", "Org AlertEngine DedupRescan");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor rescan");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra rescan");
        List<MonitorCheck> seeded = seedWindow(
                scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seeded.addAll(seedWindow(
                scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1));
        evaluate(scene.serviceId());
        Alert first = singleAlertOf(scene.serviceId());

        // janela fica saudável (só SUCCESS) → resolve AUTO
        monitorCheckRepository.deleteAll(
                seeded.stream().filter(c -> c.getOutcome() == CheckOutcome.TARGET_FAILURE)
                        .toList());
        evaluate(scene.serviceId());
        Alert resolved = alertsOf(scene.serviceId()).stream()
                .filter(a -> a.getId().equals(first.getId()))
                .findFirst().orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(AlertStatus.RESOLVED);

        // falha de novo → UM NOVO alerta (reabrir não é religar o antigo)
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        List<Alert> all = alertsOf(scene.serviceId());
        assertThat(all)
                .as("histórico: 1 RESOLVED + 1 FIRING novo")
                .hasSize(2);
        assertThat(all.stream().filter(a -> a.getStatus() == AlertStatus.FIRING))
                .hasSize(1);
        Alert second = all.stream()
                .filter(a -> a.getStatus() == AlertStatus.FIRING).findFirst().orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        Alert oldReRead = alertsOf(scene.serviceId()).stream()
                .filter(a -> a.getId().equals(first.getId()))
                .findFirst().orElseThrow();
        assertThat(oldReRead.getStatus())
                .as("o alerta antigo permanece RESOLVED intacto")
                .isEqualTo(AlertStatus.RESOLVED);
    }

    // --- #61: dedup é POR REGRA — regras diferentes convivem ------------------

    @Test
    void criterio61_dedup_e_por_regra_regras_diferentes_tem_alertas_abertos_independentes()
            throws Exception {
        Scene scene = newScene("alert-dedup-per-rule", "Org AlertEngine DedupPerRule");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor per-rule");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra A do serviço");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra B do serviço");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        evaluate(scene.serviceId());

        assertThat(openAlertsOf(scene.serviceId()))
                .as("cada regra tem o SEU alerta aberto — dedup nunca cruza regras")
                .hasSize(2);
    }

    // --- PERF-03: LIMIT 1 no pré-check --------------------------------------

    /**
     * PERF-03: o pré-check do engine precisa devolver UMA linha mesmo com
     * DUAS linhas compatíveis da mesma regra, e nunca estourar
     * {@code IncorrectResultSizeDataAccessException}.
     *
     * <p>O cenário é legítimo e corriqueiro: disparou → resolveu → disparou
     * de novo → resolveu. O índice parcial {@code uq_alerts_open_rule} da V10
     * cobre APENAS {@code status = 'FIRING'}, então as duas linhas
     * {@code RESOLVED} coexistem sem violar nada. Um {@code @Query} com
     * {@code Optional<Alert>} e sem limite encontraria as DUAS e lançaria —
     * quebrando o caminho de resolução por regra, chamado a cada ciclo. É
     * para isso que o método é derived limiting
     * ({@code findFirstByAlertRuleIdAndStatus}): o {@code setMaxRows(1)}
     * nasce na própria query.</p>
     */
    @Test
    void perf03_duas_linhas_resolved_da_mesma_regra_nunca_estouram_o_pre_check()
            throws Exception {
        Scene scene = newScene("alert-dedup-limit", "Org AlertEngine Limit");
        String ruleId = fireOnce(scene);
        String monitorId = monitorIdOf(scene);
        UUID ruleUuid = UUID.fromString(ruleId);

        // janela só com SUCCESS → o 1º alerta resolve (AUTO)
        clearTargetFailures(monitorId);
        evaluate(scene.serviceId());

        // a condição volta → UM NOVO alerta nasce (reabrir é recriar)
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        // e resolve de novo → a MESMA regra agora tem DUAS linhas RESOLVED
        clearTargetFailures(monitorId);
        evaluate(scene.serviceId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alerts"
                        + " WHERE alert_rule_id = ? AND status = 'RESOLVED'",
                Integer.class, ruleUuid))
                .as("premissa: duas linhas RESOLVED da mesma regra")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alerts"
                        + " WHERE alert_rule_id = ? AND status = 'FIRING'",
                Integer.class, ruleUuid))
                .isZero();

        assertThat(alertRepository.findFirstByAlertRuleIdAndStatus(ruleUuid, AlertStatus.RESOLVED))
                .as("derived limiting devolve UMA linha, nunca lança por tamanho")
                .isPresent();
        assertThat(alertRepository.findFirstByAlertRuleIdAndStatus(ruleUuid, AlertStatus.FIRING))
                .as("nenhum aberto: as duas já foram resolvidas")
                .isEmpty();
    }

    /** Janela 100% saudável: só ficam os checks SUCCESS do monitor. */
    private void clearTargetFailures(String monitorId) {
        jdbcTemplate.update(
                "DELETE FROM monitor_checks WHERE monitor_id = ? AND outcome = 'TARGET_FAILURE'",
                UUID.fromString(monitorId));
    }

    // --- P2-1 (revisão): concorrência REAL — N threads, exatamente 1 --------

    /**
     * Correção P2-1 da revisão: o critério 59 prova a constraint INSERT-a-INSERT
     * no caminho SEQUENCIAL; aqui N threads soltas ao mesmo tempo (latch de
     * partida) chamam o caminho de evaluate do engine para a MESMA regra — a
     * corrida real entre pré-check e insert. O desfecho é sempre EXATAMENTE UM
     * alerta FIRING: o vencedor cria, os demais caem no pré-check (touch) ou
     * na constraint (corrida benigna engolida pelo engine).
     *
     * <p>Determinístico o suficiente: jun com timeout de 30s por task + asserção
     * de contagem final (sem esperar exceção específica de thread e sem sleeps
     * longos); a única variável é quem vence, nunca a contagem.</p>
     */
    @Test
    void concorrencia_real_com_n_threads_termina_com_exatamente_um_alerta_firing()
            throws Exception {
        Scene scene = newScene("alert-dedup-parallel", "Org AlertEngine DedupParallel");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor concorrente");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra concorrente");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);

        UUID serviceId = UUID.fromString(scene.serviceId());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> runs = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    try {
                        start.await();
                        alertEngine.evaluateService(serviceId);
                    }
                    catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                // jun com timeout — falha aqui é hang, não flakiness
                run.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("o ciclo NÃO propaga exceção: a corrida vira log benigno no engine")
                .isEmpty();
        assertThat(openAlertsOf(scene.serviceId()))
                .as("N threads na MESMA regra → exatamente UM alerta FIRING")
                .hasSize(1);
        assertThat(alertsOf(scene.serviceId()))
                .as("nenhuma linha extra: pré-check/constraint absorveram as corridas")
                .hasSize(1);
    }

    // --- apoio ---------------------------------------------------------------

    private String monitorIdOf(Scene scene) throws Exception {
        return monitorRepository.findAll().stream()
                .filter(m -> m.getService().getId().toString().equals(scene.serviceId()))
                .findFirst()
                .orElseThrow()
                .getId()
                .toString();
    }
}
