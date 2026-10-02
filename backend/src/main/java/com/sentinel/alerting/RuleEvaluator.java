package com.sentinel.alerting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.monitor.dto.MetricsSummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Avaliação PURA de UMA regra contra a janela (Prompt 13) — sem escrita, sem
 * transação e sem relógio próprio: tudo que precisa chega por parâmetro e o
 * desfecho sai como {@link EvaluationResult}.
 *
 * <p><b>Semântica por tipo (nove estados possíveis, sem ambiguidade):</b></p>
 * <ul>
 *   <li><b>Numéricos</b> (AVAILABILITY, SUCCESS_RATE, AVERAGE_LATENCY,
 *       TARGET_FAILURE_COUNT): janela sem nenhum check
 *       RELEVANTE ({@code success + degraded + targetFailure == 0}) →
 *       {@code NO_DATA} (janela vazia não é 0%, não é 100%); valor nulo →
 *       {@code NO_DATA}; caso contrário compara contra o threshold com o
 *       {@link OperatorEvaluator} único → {@code FIRING} ou
 *       {@code HEALTHY}.</li>
 *   <li><b>SERVICE_STATUS</b> (os quatro resultados da revisão):</li>
 * </ul>
 * <ol>
 *   <li>status ≠ threshold → {@code HEALTHY} (o serviço NÃO está na condição
 *       da regra — resolve alerta aberto);</li>
 *   <li>status = threshold e {@code agora − statusChangedAt ≥ window} →
 *       {@code FIRING} (a condição persiste HÁ PELO MENOS a janela inteira —
 *       é o anti-falso-positivo de "UNAVAILABLE agora há 2 segundos");</li>
 *   <li>status = threshold e {@code agora − statusChangedAt < window} →
 *       {@code NO_DATA} (a condição começou agora: ainda não valeu pela
 *       janela, mas NÃO resolve o que está aberto);</li>
 *   <li>{@code statusChangedAt = null} → {@code NO_DATA} (nenhuma transição
 *       registrada, ex.: serviço recém-criado).</li>
 * </ol>
 *
 * <p><b>Fórmulas SEM duplicação:</b> os números vêm todos de
 * {@link WindowMetrics}, que delega ao {@code MonitorMetricsService} do
 * Prompt 11 — mesma fórmula, mesmo arredondamento HALF_UP e mesma regra de
 * denominador de relevantes da UI de métricas. Este classe só COMPARA.</p>
 *
 * <p><b>Janela meia-aberta {@code [agora − window.seconds, agora)}:</b>
 * recorte plano do relógio do engine — sem alinhamento à grade de buckets do
 * Prompt 11 (a grade é formato de resposta HTTP, não semântica de avaliação).
 * Um check nascido exatamente em {@code agora} pertence ao período
 * seguinte.</p>
 *
 * <p><b>Isolamento por regra:</b> qualquer falha de infra (banco, métrica)
 * vira {@code ERROR} em vez de exceção — a regra errada nunca derruba o ciclo
 * das demais nem escreve estado errado (não dispara, não resolve).</p>
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    private final WindowMetrics metrics;

    public RuleEvaluator(WindowMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Avalia a regra no contexto dado.
     *
     * @param rule regra ATIVA (o engine só avalia as ativas)
     * @param ctx  instante e estado do ciclo
     * @return o veredito — nunca {@code null}; falha de infra vira
     *         {@link EvaluationResult.Verdict#ERROR}
     */
    public EvaluationResult evaluate(AlertRule rule, AlertEvaluationContext ctx) {
        try {
            if (rule.getType() == AlertRuleType.SERVICE_STATUS) {
                return evaluateServiceStatus(rule, ctx);
            }
            return evaluateNumeric(rule, ctx);
        }
        catch (RuntimeException ex) {
            // Isolamento: a falha desta regra não pode virar exceção do ciclo.
            log.error("alert rule evaluation failed ruleId={} serviceId={}",
                    rule.getId(), ctx.serviceId(), ex);
            return EvaluationResult.error("evaluation failed for rule " + rule.getId());
        }
    }

    // --- quatro tipos numéricos --------------------------------------------

    private EvaluationResult evaluateNumeric(AlertRule rule, AlertEvaluationContext ctx) {
        Instant from = ctx.now().minusSeconds(rule.getWindow().seconds());
        MetricsSummary summary = summary(rule, from, ctx.now());

        long relevant = summary.success() + summary.degraded() + summary.targetFailure();
        if (relevant == 0) {
            // Sem evidência no período: não é 0%, não é 100%, não é falha.
            return EvaluationResult.noData(
                    "no relevant checks over " + windowLabel(rule.getWindow().seconds()));
        }

        BigDecimal actual = actualValue(rule, summary);
        if (actual == null) {
            // ex.: latência sem nenhum SUCCESS/DEGRADED na janela.
            return EvaluationResult.noData(
                    "no measurable value over " + windowLabel(rule.getWindow().seconds()));
        }

        BigDecimal threshold = rule.getThresholdNumeric();
        String detail = detail(rule, actual, threshold);
        if (OperatorEvaluator.matches(rule.getOperator(), actual, threshold)) {
            return EvaluationResult.firing(actual, detail);
        }
        return EvaluationResult.healthy(actual, detail);
    }

    /** Valor que o tipo numérico enxerga da janela (fonte: Prompt 11). */
    private static BigDecimal actualValue(AlertRule rule, MetricsSummary summary) {
        return switch (rule.getType()) {
            case AVAILABILITY -> summary.availability();
            case SUCCESS_RATE -> summary.successRate();
            case AVERAGE_LATENCY -> summary.avgLatencyMs();
            case TARGET_FAILURE_COUNT -> BigDecimal.valueOf(summary.targetFailure());
            case SERVICE_STATUS -> null; // tratado no outro caminho
        };
    }

    // --- SERVICE_STATUS ------------------------------------------------------

    private EvaluationResult evaluateServiceStatus(AlertRule rule, AlertEvaluationContext ctx) {
        var thresholdStatus = rule.getThresholdStatus();
        long windowSeconds = rule.getWindow().seconds();

        if (thresholdStatus == null || ctx.serviceStatus() == null) {
            return EvaluationResult.noData("service status not evaluated yet");
        }

        // 1. estado atual ≠ threshold → a condição não vale (resolve).
        if (ctx.serviceStatus() != thresholdStatus) {
            return EvaluationResult.healthy(null,
                    "service status " + ctx.serviceStatus()
                            + " against threshold " + thresholdStatus
                            + ", window " + windowLabel(windowSeconds));
        }

        // 4. nenhuma transição registrada → sem como medir a duração.
        if (ctx.statusChangedAt() == null) {
            return EvaluationResult.noData(
                    "service status " + thresholdStatus + " with no recorded transition");
        }

        long elapsedSeconds = ctx.now().getEpochSecond() - ctx.statusChangedAt().getEpochSecond();

        // 2. na condição HÁ PELO MENOS a janela → dispara.
        if (elapsedSeconds >= windowSeconds) {
            return EvaluationResult.firing(null,
                    "service status " + thresholdStatus + " for "
                            + durationLabel(elapsedSeconds)
                            + " (threshold " + thresholdStatus
                            + ", window " + windowLabel(windowSeconds) + ")");
        }

        // 3. na condição HÁ MENOS que a janela → ainda não vale (não resolve).
        return EvaluationResult.noData(
                "service status " + thresholdStatus + " for " + durationLabel(elapsedSeconds)
                        + ", window " + windowLabel(windowSeconds) + " not elapsed");
    }

    // --- fonte de métrica ----------------------------------------------------

    /** Janela meia-aberta da regra: escopo de monitor quando existir. */
    private MetricsSummary summary(AlertRule rule, Instant from, Instant to) {
        UUID monitorId = rule.getMonitor() == null ? null : rule.getMonitor().getId();
        if (monitorId == null) {
            return metrics.serviceWindow(rule.getService().getId(), from, to);
        }
        return metrics.monitorWindow(monitorId, from, to);
    }

    // --- detalhe legível (vira metade da mensagem do alerta) -----------------

    private static String detail(AlertRule rule, BigDecimal actual, BigDecimal threshold) {
        return switch (rule.getType()) {
            case AVAILABILITY -> "availability " + pct(actual)
                    + " against threshold " + pct(threshold)
                    + " over " + windowLabel(rule.getWindow().seconds());
            case SUCCESS_RATE -> "success rate " + pct(actual)
                    + " against threshold " + pct(threshold)
                    + " over " + windowLabel(rule.getWindow().seconds());
            case AVERAGE_LATENCY -> "average latency " + number(actual) + " ms"
                    + " against threshold " + number(threshold) + " ms"
                    + " over " + windowLabel(rule.getWindow().seconds());
            case TARGET_FAILURE_COUNT -> "target failure count " + number(actual)
                    + " against threshold " + number(threshold)
                    + " over " + windowLabel(rule.getWindow().seconds());
            case SERVICE_STATUS -> "service status"; // nunca chega aqui
        };
    }

    /** Percentual sempre com 2 casas (mesmo formato do contrato de métricas). */
    private static String pct(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Número sem casas espúrias: 800.00 → 800, 99.90 → 99.9, 3 → 3. */
    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** "5 minutes" / "1 hour" / "24 hours" — derivado dos segundos do enum. */
    static String windowLabel(long seconds) {
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = seconds / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** "30 seconds" / "5 minutes" / "2 hours" — duração já decorrida. */
    static String durationLabel(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = seconds / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
