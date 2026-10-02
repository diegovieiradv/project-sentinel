package com.sentinel.project.alert.validation;

import static com.sentinel.project.alert.validation.MatrixAssertions.assertAllowed;
import static com.sentinel.project.alert.validation.MatrixAssertions.assertRejected;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * Validação de {@link AlertRuleType#SERVICE_STATUS} (Prompt 12, Seção 47 —
 * critérios 36–42), unitária e sem container: threshold SEMPRE numérico nulo
 * e status SEMPRE presente, apenas {@code DEGRADED}/{@code UNAVAILABLE}
 * ({@code OPERATIONAL}/{@code UNKNOWN} são rejeitados de propósito — regra
 * contra "tudo saudável" é ruído) e operador exclusivamente {@code EQUAL}.
 */
class AlertRuleServiceStatusValidationTest {

    @Test
    void serviceStatusDegraded_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SERVICE_STATUS, AlertOperator.EQUAL, null, ServiceStatus.DEGRADED));
    }

    @Test
    void serviceStatusUnavailable_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SERVICE_STATUS,
                AlertOperator.EQUAL,
                null,
                ServiceStatus.UNAVAILABLE));
    }

    @Test
    void serviceStatusWithNumeric_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SERVICE_STATUS,
                        AlertOperator.EQUAL,
                        java.math.BigDecimal.valueOf(99),
                        ServiceStatus.DEGRADED),
                "thresholdNumeric: must be null for SERVICE_STATUS");
    }

    @Test
    void serviceStatusWithoutStatus_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SERVICE_STATUS, AlertOperator.EQUAL, null, null),
                "thresholdStatus: required for SERVICE_STATUS");
    }

    @Test
    void serviceStatusWithEqual_allowed() {
        assertAllowed(() -> AlertRuleValidationMatrix.validate(
                AlertRuleType.SERVICE_STATUS, AlertOperator.EQUAL, null, ServiceStatus.UNAVAILABLE));
    }

    @Test
    void serviceStatusWithLessThan_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SERVICE_STATUS,
                        AlertOperator.LESS_THAN,
                        null,
                        ServiceStatus.UNAVAILABLE),
                "operator: LESS_THAN not allowed for SERVICE_STATUS");
    }

    @Test
    void serviceStatusOperational_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SERVICE_STATUS, AlertOperator.EQUAL, null, ServiceStatus.OPERATIONAL),
                "thresholdStatus: must be DEGRADED or UNAVAILABLE for alerting");
    }

    @Test
    void serviceStatusUnknown_throws400() {
        assertRejected(
                () -> AlertRuleValidationMatrix.validate(
                        AlertRuleType.SERVICE_STATUS, AlertOperator.EQUAL, null, ServiceStatus.UNKNOWN),
                "thresholdStatus: must be DEGRADED or UNAVAILABLE for alerting");
    }
}
