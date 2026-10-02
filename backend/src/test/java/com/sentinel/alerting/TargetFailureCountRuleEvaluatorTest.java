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
 * TARGET_FAILURE_COUNT (Prompt 13) — critérios 40–44: contagem bruta de
 * target failures contra o threshold (EQUAL e GTE), saudável abaixo, janela
 * sem evidência — inclusive ZERO falhas em janela vazia — vira NO_DATA, e o
 * detalhe sai sem casas espúrias.
 */
class TargetFailureCountRuleEvaluatorTest {

    private final AlertingFixtures.FakeMetrics metrics = new AlertingFixtures.FakeMetrics();
    private final RuleEvaluator evaluator = new RuleEvaluator(metrics);

    private static AlertRule failures(AlertOperator operator, String threshold) {
        return new AlertRule(
                AlertingFixtures.service(),
                "Target failures " + operator,
                null,
                AlertRuleType.TARGET_FAILURE_COUNT,
                operator,
                new BigDecimal(threshold),
                null,
                EvaluationWindow.FIFTEEN_MINUTES,
                AlertSeverity.SEV3);
    }

    @Test
    void criterio40_dispara_em_equal_quando_contagem_iguala_threshold() {
        metrics.summary = counts(7, 0, 3, 0); // 3 target failures

        EvaluationResult result = evaluator.evaluate(
                failures(AlertOperator.EQUAL, "3"), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("3");
    }

    @Test
    void criterio41_dispara_em_gte_quando_contagem_supera_threshold() {
        metrics.summary = counts(3, 0, 7, 0); // 7 ≥ 5

        EvaluationResult result = evaluator.evaluate(
                failures(AlertOperator.GREATER_THAN_OR_EQUAL, "5"), ctx(null, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("7");
    }

    @Test
    void criterio42_saudavel_quando_contagem_fica_abaixo_do_threshold() {
        metrics.summary = counts(10, 0, 3, 0); // 3 < 5

        EvaluationResult gte = evaluator.evaluate(
                failures(AlertOperator.GREATER_THAN_OR_EQUAL, "5"), ctx(null, null));
        assertThat(gte.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(gte.value()).isEqualByComparingTo("3");

        metrics.summary = counts(10, 0, 2, 0); // 2 ≠ 3 no EQUAL
        EvaluationResult equal = evaluator.evaluate(
                failures(AlertOperator.EQUAL, "3"), ctx(null, null));
        assertThat(equal.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(equal.value()).isEqualByComparingTo("2");
    }

    @Test
    void criterio43_janela_sem_evidencia_eh_no_data_mesmo_com_zero_falhas() {
        metrics.summary = counts(0, 0, 0, 0); // janela vazia: 0 falhas "inocentes"
        EvaluationResult empty = evaluator.evaluate(
                failures(AlertOperator.GREATER_THAN_OR_EQUAL, "1"), ctx(null, null));
        assertThat(empty.verdict())
                .as("zero falhas sem dado nenhum não é saúde")
                .isEqualTo(EvaluationResult.Verdict.NO_DATA);

        metrics.summary = counts(0, 0, 0, 5); // só falha interna: sem relevantes
        EvaluationResult internalOnly = evaluator.evaluate(
                failures(AlertOperator.GREATER_THAN_OR_EQUAL, "1"), ctx(null, null));
        assertThat(internalOnly.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
    }

    @Test
    void criterio44_detalhe_com_contagem_inteira_e_janela_legivel() {
        metrics.summary = counts(7, 0, 3, 0);

        EvaluationResult result = evaluator.evaluate(
                failures(AlertOperator.EQUAL, "3.0"), ctx(null, null));

        assertThat(result.detail()).isEqualTo(
                "target failure count 3 against threshold 3 over 15 minutes");
    }
}
