package com.sentinel.alerting;

import static com.sentinel.alerting.AlertingFixtures.NOW;
import static com.sentinel.alerting.AlertingFixtures.counts;
import static com.sentinel.alerting.AlertingFixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.junit.jupiter.api.Test;

/**
 * AVAILABILITY (Prompt 13) — critérios 24–29: dispara abaixo do threshold,
 * é estrito no limite, janela vazia/sem relevantes vira NO_DATA, o recorte é
 * meia-aberta {@code [now − window, now)}, o escopo de monitor roteia para a
 * janela do monitor e o detalhe segue o formato de percentual com 2 casas.
 */
class AvailabilityRuleEvaluatorTest {

    private final AlertingFixtures.FakeMetrics metrics = new AlertingFixtures.FakeMetrics();
    private final RuleEvaluator evaluator = new RuleEvaluator(metrics);

    // --- #24–#25 veredito numérico ------------------------------------------

    @Test
    void criterio24_dispara_abaixo_do_threshold() {
        metrics.summary = counts(99, 0, 1, 0); // 99.00% < 99.9%
        AlertRule rule = AlertingFixtures.availabilityBelow("99.9");

        EvaluationResult result = evaluator.evaluate(rule, ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("99.00");
    }

    @Test
    void criterio25_saudavel_no_limite_exato_e_acima() {
        // no limite: 999/1000 = 99.90 — LESS_THAN é estrito, não dispara
        metrics.summary = counts(999, 0, 1, 0);
        EvaluationResult atThreshold = evaluator.evaluate(
                AlertingFixtures.availabilityBelow("99.9"), ctx(null, null));
        assertThat(atThreshold.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(atThreshold.value()).isEqualByComparingTo("99.90");

        metrics.summary = counts(100, 0, 0, 0);
        EvaluationResult above = evaluator.evaluate(
                AlertingFixtures.availabilityBelow("99.9"), ctx(null, null));
        assertThat(above.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(above.value()).isEqualByComparingTo("100.00");
    }

    // --- #26 janela sem evidência -------------------------------------------

    @Test
    void criterio26_janela_vazia_ou_sem_relevantes_eh_no_data_nunca_saude() {
        metrics.summary = counts(0, 0, 0, 0);
        EvaluationResult empty = evaluator.evaluate(
                AlertingFixtures.availabilityBelow("99.9"), ctx(null, null));
        assertThat(empty.verdict())
                .as("janela vazia não é 100% nem 0%")
                .isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(empty.value()).isNull();

        metrics.summary = counts(0, 0, 0, 5); // só falha interna → sem relevantes
        EvaluationResult internalOnly = evaluator.evaluate(
                AlertingFixtures.availabilityBelow("99.9"), ctx(null, null));
        assertThat(internalOnly.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
    }

    // --- #27 recorte meia-aberto --------------------------------------------

    @Test
    void criterio27_recorta_janela_meia_aberta_agora_menos_window() {
        metrics.summary = counts(100, 0, 0, 0);
        AlertRule rule = AlertingFixtures.availabilityBelow("99.9");

        evaluator.evaluate(rule, ctx(null, null));

        assertThat(metrics.serviceCalls).isEqualTo(1);
        assertThat(metrics.from).isEqualTo(NOW.minusSeconds(EvaluationWindow.FIFTEEN_MINUTES.seconds()));
        assertThat(metrics.to)
                .as("fim é exclusivo: agora, não agora+epsilon")
                .isEqualTo(NOW);
        assertThat(metrics.to).isAfter(metrics.from);
    }

    // --- #28 escopo de monitor ------------------------------------------------

    @Test
    void criterio28_escopo_de_monitor_usa_janela_do_monitor() {
        Monitor monitor = monitorWithId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        AlertRule rule = new AlertRule(
                AlertingFixtures.service(),
                "Latency scoped",
                monitor,
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN,
                new BigDecimal("99.9"),
                null,
                EvaluationWindow.FIVE_MINUTES,
                com.sentinel.project.alert.model.AlertSeverity.SEV2);
        metrics.summary = counts(1, 0, 1, 0);

        evaluator.evaluate(rule, ctx(null, null));

        assertThat(metrics.monitorCalls).isEqualTo(1);
        assertThat(metrics.serviceCalls)
                .as("escopo de monitor nunca consulta a janela do serviço")
                .isZero();
        assertThat(metrics.monitorId).isEqualTo(monitor.getId());
        assertThat(metrics.from).isEqualTo(NOW.minusSeconds(EvaluationWindow.FIVE_MINUTES.seconds()));
    }

    // --- #29 detalhe no formato de percentual --------------------------------

    @Test
    void criterio29_detalhe_com_percentual_duas_casas_e_janela_legivel() {
        metrics.summary = counts(97, 0, 3, 0); // 97.00%

        EvaluationResult result = evaluator.evaluate(
                AlertingFixtures.availabilityBelow("99.9"), ctx(null, null));

        assertThat(result.detail()).isEqualTo(
                "availability 97.00% against threshold 99.90% over 15 minutes");
    }

    // --- helpers --------------------------------------------------------------

    private static Monitor monitorWithId(UUID id) {
        Monitor monitor = new Monitor(
                null, "Scoped", "https://93.184.216.34/health", MonitorMethod.GET, 60, 5000);
        try {
            var field = Monitor.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(monitor, id);
        }
        catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot inject monitor id", ex);
        }
        return monitor;
    }

    /** "Agora" do contexto — exposto para asserções de instante. */
    private static Instant fixedNow() {
        return NOW;
    }
}
