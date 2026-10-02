package com.sentinel.project.alert.validation;

import static com.sentinel.project.alert.validation.MatrixAssertions.assertAllowed;
import static com.sentinel.project.alert.validation.MatrixAssertions.assertRejected;

import java.math.BigDecimal;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * Validação de {@link AlertRuleType#AVERAGE_LATENCY} (Prompt 12, Seção 45 —
 * critérios 24–30), unitária e sem container: latência em ms deve ser
 * {@code > 0} (fração permitida com até 4 casas — o limite do
 * {@code NUMERIC(19,4)} da V9), status proibido e a matriz de operadores
 * ({@code >}/{@code ≥} apenas).
 */
class AlertRuleLatencyValidationTest {

    private static final BigDecimal ANY_LATENCY = new BigDecimal("800");

    @Test
    void latencyThresholdPositive_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVERAGE_LATENCY, AlertOperator.GREATER_THAN, ANY_LATENCY, null));
    }

    @Test
    void latencyThresholdZero_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVERAGE_LATENCY, AlertOperator.GREATER_THAN, BigDecimal.ZERO, null),
                "thresholdNumeric: must be greater than 0 for AVERAGE_LATENCY");
    }

    @Test
    void latencyThresholdNegative_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVERAGE_LATENCY,
                        AlertOperator.GREATER_THAN,
                        new BigDecimal("-50"),
                        null),
                "thresholdNumeric: must be greater than 0 for AVERAGE_LATENCY");
    }

    @Test
    void latencyThresholdFractional_allowed() {
        // escala 1 ≤ 4: fração legítima (ex.: 800.5 ms) passa
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVERAGE_LATENCY,
                AlertOperator.GREATER_THAN_OR_EQUAL,
                new BigDecimal("800.5"),
                null));
        // escala 4 = teto exato do NUMERIC(19,4)
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVERAGE_LATENCY,
                AlertOperator.GREATER_THAN,
                new BigDecimal("123.4567"),
                null));
    }

    @Test
    void latencyThresholdFiveDecimals_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVERAGE_LATENCY,
                        AlertOperator.GREATER_THAN,
                        new BigDecimal("123.45678"),
                        null),
                "thresholdNumeric: must have at most 4 decimal places for AVERAGE_LATENCY");
    }

    @Test
    void latencyWithStatusThreshold_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVERAGE_LATENCY,
                        AlertOperator.GREATER_THAN,
                        ANY_LATENCY,
                        ServiceStatus.DEGRADED),
                "thresholdStatus: must be null for AVERAGE_LATENCY");
    }

    @Test
    void latencyWithGreaterThan_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.AVERAGE_LATENCY, AlertOperator.GREATER_THAN, ANY_LATENCY, null));
    }

    @Test
    void latencyWithLessThan_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.AVERAGE_LATENCY, AlertOperator.LESS_THAN, ANY_LATENCY, null),
                "operator: LESS_THAN not allowed for AVERAGE_LATENCY");
    }
}
