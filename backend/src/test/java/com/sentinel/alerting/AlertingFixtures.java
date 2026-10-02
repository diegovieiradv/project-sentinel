package com.sentinel.alerting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Organization;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.dto.MetricsSummary;

/**
 * Fixtures dos testes de UNIDADE do Alert Engine (Prompt 13) — regras e
 * serviços transitórios (nunca persistidos) + um {@link WindowMetrics} fake
 * de uma linha, para avaliar sem Spring, sem banco e sem relógio real.
 *
 * <p><b>Fórmulas espelhadas só no fake:</b> {@link #counts} recalcula
 * disponibilidade/taxa com a regra do Prompt 11 (relevantes =
 * success+degraded+targetFailure, HALF_UP 2 casas, zero relevante → null) —
 * em produção a fonte é o {@code MonitorMetricsService}; aqui o fake é o
 * oráculo do teste.</p>
 */
final class AlertingFixtures {

    /** Mesmo "agora" do relógio congelado dos testes de integração. */
    static final Instant NOW = Instant.parse("2026-03-01T12:34:56Z");

    private AlertingFixtures() {
    }

    // --- entidades transitórias ---------------------------------------------

    static Service service() {
        Organization organization = new Organization("Alert Engine Corp");
        Project project = new Project(organization, "api", "projeto");
        return new Service(project, "checkout", "serviço");
    }

    /** Regra Service-level transitória com a condição dada. */
    static AlertRule rule(
            AlertRuleType type,
            AlertOperator operator,
            BigDecimal thresholdNumeric,
            ServiceStatus thresholdStatus,
            EvaluationWindow window) {
        return new AlertRule(
                service(),
                "Rule " + type,
                null,
                type,
                operator,
                thresholdNumeric,
                thresholdStatus,
                window,
                AlertSeverity.SEV2);
    }

    /** Regra AVAILABILITY &lt; threshold em 15 minutos (caso canônico). */
    static AlertRule availabilityBelow(String threshold) {
        return rule(
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN,
                new BigDecimal(threshold),
                null,
                EvaluationWindow.FIFTEEN_MINUTES);
    }

    /** Contexto do ciclo: serviço qualquer, status/instante controlados. */
    static AlertEvaluationContext ctx(ServiceStatus status, Instant statusChangedAt) {
        return new AlertEvaluationContext(UUID.randomUUID(), status, statusChangedAt, NOW);
    }

    // --- métrica fake --------------------------------------------------------

    /**
     * {@link WindowMetrics} de teste: devolve o resumo preparado e CAPTURA a
     * janela chamada (para provar o recorte meia-aberta
     * {@code [now − window, now)}).
     */
    static final class FakeMetrics implements WindowMetrics {

        MetricsSummary summary = counts(0, 0, 0, 0);
        UUID serviceId;
        UUID monitorId;
        Instant from;
        Instant to;
        int serviceCalls;
        int monitorCalls;

        @Override
        public MetricsSummary serviceWindow(UUID id, Instant from, Instant to) {
            this.serviceId = id;
            this.from = from;
            this.to = to;
            this.serviceCalls++;
            return summary;
        }

        @Override
        public MetricsSummary monitorWindow(UUID id, Instant from, Instant to) {
            this.monitorId = id;
            this.from = from;
            this.to = to;
            this.monitorCalls++;
            return summary;
        }
    }

    /**
     * Contagens brutas com as taxas derivadas da regra do Prompt 11
     * (oráculo do teste — mesma fórmula da produção).
     */
    static MetricsSummary counts(long success, long degraded, long targetFailure, long internal) {
        long relevant = success + degraded + targetFailure;
        BigDecimal availability = relevant == 0
                ? null
                : BigDecimal.valueOf((success + degraded) * 100)
                        .divide(BigDecimal.valueOf(relevant), 2, RoundingMode.HALF_UP);
        BigDecimal successRate = relevant == 0
                ? null
                : BigDecimal.valueOf(success * 100)
                        .divide(BigDecimal.valueOf(relevant), 2, RoundingMode.HALF_UP);
        return new MetricsSummary(
                success + degraded + targetFailure + internal,
                success,
                degraded,
                targetFailure,
                internal,
                availability,
                successRate,
                null,
                null,
                null);
    }

    /** Contagens com uma latência média explícita (casos AVERAGE_LATENCY). */
    static MetricsSummary withLatency(long success, long targetFailure, String avgLatencyMs) {
        MetricsSummary base = counts(success, 0, targetFailure, 0);
        return new MetricsSummary(
                base.totalChecks(),
                base.success(),
                base.degraded(),
                base.targetFailure(),
                base.sentinelInternalFailure(),
                base.availability(),
                base.successRate(),
                new BigDecimal(avgLatencyMs),
                null,
                null);
    }
}
