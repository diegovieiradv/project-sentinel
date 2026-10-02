package com.sentinel.project.alert.validation;

import static com.sentinel.project.alert.validation.MatrixAssertions.assertAllowed;
import static com.sentinel.project.alert.validation.MatrixAssertions.assertRejected;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * Validação de {@link AlertRuleType#SUCCESS_RATE} (Prompt 12, Seção 44 —
 * critérios 18–23), unitária e sem container: mesma faixa de percentual de
 * {@code AVAILABILITY} (0..100, escala ≤ 2) e a MESMA matriz de operadores
 * ({@code <}/{@code ≤}) — o teste prova que os dois tipos de percentual não
 * divergem.
 */
class AlertRuleSuccessRateValidationTest {

    private static final BigDecimal ANY_RATE = new BigDecimal("99.5");

    @Test
    void successRateThresholdZero_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SUCCESS_RATE, AlertOperator.LESS_THAN, BigDecimal.ZERO, null));
    }

    @Test
    void successRateThreshold100_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SUCCESS_RATE,
                AlertOperator.LESS_THAN,
                BigDecimal.valueOf(100),
                null));
    }

    @Test
    void successRateThresholdNegative_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SUCCESS_RATE,
                        AlertOperator.LESS_THAN,
                        new BigDecimal("-1"),
                        null),
                "thresholdNumeric: must be between 0 and 100 for SUCCESS_RATE");
    }

    @Test
    void successRateThreshold101_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SUCCESS_RATE,
                        AlertOperator.LESS_THAN,
                        new BigDecimal("101"),
                        null),
                "thresholdNumeric: must be between 0 and 100 for SUCCESS_RATE");
    }

    @Test
    void successRateWithoutNumeric_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SUCCESS_RATE, AlertOperator.LESS_THAN, null, null),
                "thresholdNumeric: required for SUCCESS_RATE");
    }

    @Test
    void successRateWithStatusThreshold_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SUCCESS_RATE,
                        AlertOperator.LESS_THAN,
                        ANY_RATE,
                        ServiceStatus.UNAVAILABLE),
                "thresholdStatus: must be null for SUCCESS_RATE");
    }

    @Test
    void successRateWithLessThan_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SUCCESS_RATE, AlertOperator.LESS_THAN, ANY_RATE, null));
    }

    @Test
    void successRateWithGreaterThan_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SUCCESS_RATE, AlertOperator.GREATER_THAN, ANY_RATE, null),
                "operator: GREATER_THAN not allowed for SUCCESS_RATE");
    }
}
