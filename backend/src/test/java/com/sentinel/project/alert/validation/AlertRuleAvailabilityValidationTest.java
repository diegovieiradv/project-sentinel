package com.sentinel.project.alert.validation;

import static com.sentinel.project.alert.validation.MatrixAssertions.assertAllowed;
import static com.sentinel.project.alert.validation.MatrixAssertions.assertRejected;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * Validação de {@link AlertRuleType#AVAILABILITY} (Prompt 12, Seção 43 —
 * critérios 10–17), unitária e sem container: percentual em [0, 100],
 * threshold numérico obrigatório, status proibido e a matriz de operadores
 * ({@code <}/{@code ≤} apenas).
 */
class AlertRuleAvailabilityValidationTest {

    private static final BigDecimal ANY_PERCENT = new BigDecimal("99.9");

    @Test
    void availabilityThresholdZero_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVAILABILITY, AlertOperator.LESS_THAN, BigDecimal.ZERO, null));
    }

    @Test
    void availabilityThreshold100_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN,
                BigDecimal.valueOf(100),
                null));
    }

    @Test
    void availabilityThresholdNegative_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY,
                        AlertOperator.LESS_THAN,
                        new BigDecimal("-0.01"),
                        null),
                "thresholdNumeric: must be between 0 and 100 for AVAILABILITY");
    }

    @Test
    void availabilityThreshold101_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY,
                        AlertOperator.LESS_THAN,
                        new BigDecimal("100.01"),
                        null),
                "thresholdNumeric: must be between 0 and 100 for AVAILABILITY");
    }

    @Test
    void availabilityWithoutNumeric_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY, AlertOperator.LESS_THAN, null, null),
                "thresholdNumeric: required for AVAILABILITY");
    }

    @Test
    void availabilityWithStatusThreshold_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY,
                        AlertOperator.LESS_THAN,
                        ANY_PERCENT,
                        ServiceStatus.DEGRADED),
                "thresholdStatus: must be null for AVAILABILITY");
    }

    @Test
    void availabilityWithLessThan_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVAILABILITY, AlertOperator.LESS_THAN, ANY_PERCENT, null));
    }

    @Test
    void availabilityWithGreaterThan_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY,
                        AlertOperator.GREATER_THAN,
                        ANY_PERCENT,
                        null),
                "operator: GREATER_THAN not allowed for AVAILABILITY");
    }

    // --- escala do percentual (2 casas) -------------------------------------

    @Test
    void availabilityWithTwoDecimals_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN_OR_EQUAL,
                new BigDecimal("99.95"),
                null));
    }

    @Test
    void availabilityWithThreeDecimals_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVAILABILITY,
                        AlertOperator.LESS_THAN,
                        new BigDecimal("99.999"),
                        null),
                "thresholdNumeric: must have at most 2 decimal places for AVAILABILITY");
    }
}
