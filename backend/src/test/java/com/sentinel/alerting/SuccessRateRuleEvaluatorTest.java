package com.sentinel.alerting;

import static com.sentinel.alerting.AlertingFixtures.ctx;
import static com.sentinel.alerting.AlertingFixtures.counts;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;

import org.junit.jupiter.api.Test;

/**
 * SUCCESS_RATE (Prompt 13) — critérios 30–33: dispara quando a taxa de sucesso
 * cai abaixo do threshold, fica saudável acima/limite, janela sem relevantes é
 * NO_DATA e o detalhe sai no formato de percentual com 2 casas.
 */
class SuccessRateRuleEvaluatorTest {

    private final AlertingFixtures.FakeMetrics metrics = new AlertingFixtures.FakeMetrics();
    private final RuleEvaluator evaluator = new RuleEvaluator(metrics);

    private static AlertRule successRateBelow(String threshold, EvaluationWindow window) {
        return new AlertRule(
                AlertingFixtures.service(),
                "Success rate below " + threshold,
                null,
                AlertRuleType.SUCCESS_RATE,
                AlertOperator.LESS_THAN,
                new BigDecimal(threshold),
                null,
                window,
                AlertSeverity.SEV2);
    }

    @Test
    void criterio30_dispara_quando_a_taxa_de_sucesso_cai_abaixo_do_threshold() {
        metrics.summary = counts(80, 0, 20, 0); // 80.00% < 85%

        EvaluationResult result = evaluator.evaluate(
                successRateBelow("85", EvaluationWindow.FIVE_MINUTES), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("80.00");
    }

    @Test
    void criterio31_saudavel_acima_e_no_limite_exato() {
        metrics.summary = counts(85, 0, 15, 0); // 85.00 — estrito, não dispara
        EvaluationResult atThreshold = evaluator.evaluate(
                successRateBelow("85", EvaluationWindow.FIVE_MINUTES), ctx(null, null));
        assertThat(atThreshold.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(atThreshold.value()).isEqualByComparingTo("85.00");

        metrics.summary = counts(90, 0, 10, 0); // 90.00% > 85%
        EvaluationResult above = evaluator.evaluate(
                successRateBelow("85", EvaluationWindow.FIVE_MINUTES), ctx(null, null));
        assertThat(above.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(above.value()).isEqualByComparingTo("90.00");
    }

    @Test
    void criterio32_janela_sem_relevantes_eh_no_data() {
        metrics.summary = counts(0, 0, 0, 0);

        EvaluationResult result = evaluator.evaluate(
                successRateBelow("85", EvaluationWindow.FIVE_MINUTES), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.value()).isNull();
        assertThat(result.detail()).contains("no relevant checks over 5 minutes");
    }

    @Test
    void criterio33_detalhe_com_percentual_duas_casas_e_janela_legivel() {
        metrics.summary = counts(80, 0, 20, 0);

        EvaluationResult result = evaluator.evaluate(
                successRateBelow("85", EvaluationWindow.TWENTY_FOUR_HOURS), ctx(null, null));

        assertThat(result.detail()).isEqualTo(
                "success rate 80.00% against threshold 85.00% over 24 hours");
    }
}
