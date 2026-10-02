package com.sentinel.alerting;

import static com.sentinel.alerting.AlertingFixtures.NOW;
import static com.sentinel.alerting.AlertingFixtures.ctx;
import static com.sentinel.alerting.AlertingFixtures.counts;
import static com.sentinel.alerting.AlertingFixtures.withLatency;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;

import org.junit.jupiter.api.Test;

/**
 * AVERAGE_LATENCY (Prompt 13) — critérios 34–39: dispara acima do threshold,
 * é estrito no limite, janela sem checks ou sem latência medível é NO_DATA,
 * o recorte é meia-aberto e o detalhe sai sem casas espúrias
 * ({@code 1240 ms}, não {@code 1240.00 ms}).
 */
class AverageLatencyRuleEvaluatorTest {

    private final AlertingFixtures.FakeMetrics metrics = new AlertingFixtures.FakeMetrics();
    private final RuleEvaluator evaluator = new RuleEvaluator(metrics);

    private static AlertRule latencyAbove(String threshold) {
        return new AlertRule(
                AlertingFixtures.service(),
                "Latency above " + threshold + "ms",
                null,
                AlertRuleType.AVERAGE_LATENCY,
                AlertOperator.GREATER_THAN,
                new BigDecimal(threshold),
                null,
                EvaluationWindow.FIVE_MINUTES,
                AlertSeverity.SEV2);
    }

    @Test
    void criterio34_dispara_acima_do_threshold() {
        metrics.summary = withLatency(100, 0, "1240"); // 1240 > 800

        EvaluationResult result = evaluator.evaluate(latencyAbove("800"), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("1240");
    }

    @Test
    void criterio35_saudavel_no_limite_exato_e_abaixo() {
        metrics.summary = withLatency(100, 0, "800"); // 800 > 800 = falso
        EvaluationResult atThreshold = evaluator.evaluate(latencyAbove("800"), ctx(null, null));
        assertThat(atThreshold.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(atThreshold.value()).isEqualByComparingTo("800");

        metrics.summary = withLatency(100, 0, "500");
        EvaluationResult below = evaluator.evaluate(latencyAbove("800"), ctx(null, null));
        assertThat(below.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(below.value()).isEqualByComparingTo("500");
    }

    @Test
    void criterio36_janela_sem_checks_relevantes_eh_no_data() {
        metrics.summary = counts(0, 0, 0, 0);

        EvaluationResult result = evaluator.evaluate(latencyAbove("800"), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.value()).isNull();
    }

    @Test
    void criterio37_sem_latencia_medivel_eh_no_data_nunca_saude() {
        // relevantes > 0 (só target failures), mas nenhuma latência registrada
        metrics.summary = counts(0, 0, 10, 0);

        EvaluationResult result = evaluator.evaluate(latencyAbove("800"), ctx(null, null));

        assertThat(result.verdict())
                .as("sem latência medida não dá para dizer saudável")
                .isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.detail()).contains("no measurable value over 5 minutes");
    }

    @Test
    void criterio38_recorta_janela_meia_aberta_agora_menos_window() {
        metrics.summary = withLatency(100, 0, "500");

        evaluator.evaluate(latencyAbove("800"), ctx(null, null));

        assertThat(metrics.serviceCalls).isEqualTo(1);
        assertThat(metrics.from).isEqualTo(NOW.minusSeconds(300));
        assertThat(metrics.to).isEqualTo(NOW);
    }

    @Test
    void criterio39_detalhe_sem_casas_espurias_e_com_janela_legivel() {
        metrics.summary = withLatency(100, 0, "1240.00");

        EvaluationResult result = evaluator.evaluate(latencyAbove("800"), ctx(null, null));

        assertThat(result.detail()).isEqualTo(
                "average latency 1240 ms against threshold 800 ms over 5 minutes");
    }
}
