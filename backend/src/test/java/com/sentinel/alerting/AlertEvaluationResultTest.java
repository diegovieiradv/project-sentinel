package com.sentinel.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.sentinel.alerting.EvaluationResult.Verdict;

import org.junit.jupiter.api.Test;

/**
 * Desfecho da avaliação (Prompt 13) — critérios 19–23: os quatro vereditos
 * sem sobreposição, valores nulos nos vereditos sem número observado e
 * imutabilidade/igualdade estrutural do record.
 */
class AlertEvaluationResultTest {

    private static final String DETAIL = "availability 97.50% against threshold 99.90%";

    @Test
    void criterio19_firing_carrega_valor_e_detalhe() {
        EvaluationResult result = EvaluationResult.firing(new BigDecimal("97.5"), DETAIL);

        assertThat(result.verdict()).isEqualTo(Verdict.FIRING);
        assertThat(result.value()).isEqualByComparingTo("97.5");
        assertThat(result.detail()).isEqualTo(DETAIL);
    }

    @Test
    void criterio20_healthy_carrega_valor_observado() {
        EvaluationResult result = EvaluationResult.healthy(new BigDecimal("100.00"), DETAIL);

        assertThat(result.verdict()).isEqualTo(Verdict.HEALTHY);
        assertThat(result.value()).isEqualByComparingTo("100.00");
        assertThat(result.detail()).isEqualTo(DETAIL);
    }

    @Test
    void criterio21_no_data_sem_valor_e_nunca_dispara() {
        EvaluationResult result = EvaluationResult.noData("no relevant checks over 15 minutes");

        assertThat(result.verdict()).isEqualTo(Verdict.NO_DATA);
        assertThat(result.value()).isNull();
        assertThat(result.detail()).contains("no relevant checks");
    }

    @Test
    void criterio22_error_sem_valor_somente_para_falha_de_infra() {
        EvaluationResult result = EvaluationResult.error("evaluation failed for rule x");

        assertThat(result.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(result.value()).isNull();
        assertThat(result.detail()).contains("evaluation failed");
    }

    @Test
    void criterio23_vereditos_sao_quatro_exclusivos_e_o_record_e_imutavel() {
        assertThat(Verdict.values())
                .as("quatro estados, sem sobreposição")
                .containsExactly(Verdict.FIRING, Verdict.HEALTHY, Verdict.NO_DATA, Verdict.ERROR);

        EvaluationResult result = EvaluationResult.firing(new BigDecimal("1"), DETAIL);
        assertThat(result)
                .as("igualdade estrutural do record")
                .isEqualTo(new EvaluationResult(Verdict.FIRING, new BigDecimal("1"), DETAIL));
        assertThat(result)
                .as("detalhe diferente → resultado diferente")
                .isNotEqualTo(new EvaluationResult(Verdict.FIRING, new BigDecimal("1"), "outro"));

        // record tem accessor público, não setter: o estado é fixo após criação
        assertThat(result.getClass().getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.startsWith("set"));
    }
}
