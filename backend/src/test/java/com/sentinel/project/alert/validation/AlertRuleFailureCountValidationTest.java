package com.sentinel.project.alert.validation;

import static com.sentinel.project.alert.validation.MatrixAssertions.assertAllowed;
import static com.sentinel.project.alert.validation.MatrixAssertions.assertRejected;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * Validação de {@link AlertRuleType#TARGET_FAILURE_COUNT} (Prompt 12, Seção
 * 46 — critérios 31–35), unitária e sem container: contagem inteira ≥ 0
 * (zero é válido — "nenhuma falha tolerada"), fração rejeitada e a matriz de
 * operadores ({@code >}/{@code ≥}/{@code =}).
 */
class AlertRuleFailureCountValidationTest {

    private static final BigDecimal THREE = BigDecimal.valueOf(3);

    @Test
    void failureCountInteger_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.TARGET_FAILURE_COUNT, AlertOperator.GREATER_THAN, THREE, null));
    }

    @Test
    void failureCountZero_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.TARGET_FAILURE_COUNT, AlertOperator.GREATER_THAN, BigDecimal.ZERO, null));
    }

    @Test
    void failureCountNegative_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.TARGET_FAILURE_COUNT,
                        AlertOperator.GREATER_THAN,
                        new BigDecimal("-1"),
                        null),
                "thresholdNumeric: must be greater than or equal to 0 for TARGET_FAILURE_COUNT");
    }

    @Test
    void failureCountFractional_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.TARGET_FAILURE_COUNT,
                        AlertOperator.GREATER_THAN,
                        new BigDecimal("2.5"),
                        null),
                "thresholdNumeric: must be an integer for TARGET_FAILURE_COUNT");
    }

    @Test
    void failureCountWithEqual_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.TARGET_FAILURE_COUNT, AlertOperator.EQUAL, THREE, null));
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.TARGET_FAILURE_COUNT,
                AlertOperator.GREATER_THAN_OR_EQUAL,
                BigDecimal.ZERO,
                null));
    }

    @Test
    void failureCountWithLessThan_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.TARGET_FAILURE_COUNT, AlertOperator.LESS_THAN, THREE, null),
                "operator: LESS_THAN not allowed for TARGET_FAILURE_COUNT");
    }
}
