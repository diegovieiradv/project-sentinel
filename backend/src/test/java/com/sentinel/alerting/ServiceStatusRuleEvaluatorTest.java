package com.sentinel.alerting;

import static com.sentinel.alerting.AlertingFixtures.NOW;
import static com.sentinel.alerting.AlertingFixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.ServiceStatus;

import org.junit.jupiter.api.Test;

/**
 * SERVICE_STATUS (Prompt 13) — critérios 45–50: os QUATRO resultados da
 * revisão, sem ambiguidade. status ≠ threshold → HEALTHY (resolve); status =
 * threshold HÁ PELO MENOS a janela → FIRING; HÁ MENOS que a janela →
 * NO_DATA (não resolve); {@code statusChangedAt} ou status ausente →
 * NO_DATA. Critério de rótulos cobre as durações ("30 seconds") e janelas
 * ("1 hour") dos detalhes.
 */
class ServiceStatusRuleEvaluatorTest {

    private final AlertingFixtures.FakeMetrics metrics = new AlertingFixtures.FakeMetrics();
    private final RuleEvaluator evaluator = new RuleEvaluator(metrics);

    private static AlertRule statusRule(ServiceStatus threshold, EvaluationWindow window) {
        return new AlertRule(
                AlertingFixtures.service(),
                "Status is " + threshold,
                null,
                AlertRuleType.SERVICE_STATUS,
                AlertOperator.EQUAL,
                null,
                threshold,
                window,
                AlertSeverity.SEV1);
    }

    @Test
    void criterio45_status_diferente_do_threshold_eh_healthy_e_resolve() {
        EvaluationResult result = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.OPERATIONAL, NOW.minusSeconds(7200)));

        assertThat(result.verdict())
                .as("serviço saudável → HEALTHY, o lifecycle resolve o alerta aberto")
                .isEqualTo(EvaluationResult.Verdict.HEALTHY);
        assertThat(result.value()).isNull();
        assertThat(result.detail())
                .contains("service status OPERATIONAL")
                .contains("threshold UNAVAILABLE");
        assertThat(metrics.serviceCalls)
                .as("SERVICE_STATUS não consulta métrica de janela")
                .isZero();
    }

    @Test
    void criterio46_status_na_condicao_pelo_menos_a_janela_dispara() {
        // na condição há exatamente 15 minutos (limite inclusivo)
        EvaluationResult atBoundary = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.UNAVAILABLE, NOW.minusSeconds(900)));
        assertThat(atBoundary.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(atBoundary.value()).isNull();
        assertThat(atBoundary.detail()).contains("for 15 minutes");

        // e há MUITO mais que a janela
        EvaluationResult longer = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.UNAVAILABLE, NOW.minusSeconds(7200)));
        assertThat(longer.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(longer.detail()).contains("for 2 hours");
    }

    @Test
    void criterio47_status_na_condicao_menos_que_a_janela_eh_no_data_sem_resolver() {
        EvaluationResult result = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.UNAVAILABLE, NOW.minusSeconds(300)));

        assertThat(result.verdict())
                .as("UNAVAILABLE há 2s não vale pela janela e NÃO resolve o que está aberto")
                .isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.value()).isNull();
        assertThat(result.detail()).contains("not elapsed");
    }

    @Test
    void criterio48_sem_transicao_registrada_eh_no_data() {
        EvaluationResult result = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.UNAVAILABLE, null));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.detail()).contains("no recorded transition");
    }

    @Test
    void criterio49_status_do_servico_ausente_no_ciclo_eh_no_data() {
        EvaluationResult result = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(null, NOW.minusSeconds(9000)));

        assertThat(result.verdict())
                .as("sem status avaliado não há o que comparar")
                .isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(result.detail()).contains("not evaluated yet");
    }

    @Test
    void criterio50_labels_de_duracao_e_janela_nos_detalhes() {
        // duração em segundos:30s na condição, janela de 15 minutos
        EvaluationResult seconds = evaluator.evaluate(
                statusRule(ServiceStatus.UNAVAILABLE, EvaluationWindow.FIFTEEN_MINUTES),
                ctx(ServiceStatus.UNAVAILABLE, NOW.minusSeconds(30)));
        assertThat(seconds.verdict()).isEqualTo(EvaluationResult.Verdict.NO_DATA);
        assertThat(seconds.detail()).contains("for 30 seconds").contains("window 15 minutes");

        // janela de 1 hora com condição já durando 1 hora → FIRING
        EvaluationResult hour = evaluator.evaluate(
                statusRule(ServiceStatus.DEGRADED, EvaluationWindow.ONE_HOUR),
                ctx(ServiceStatus.DEGRADED, NOW.minusSeconds(3600)));
        assertThat(hour.verdict()).isEqualTo(EvaluationResult.Verdict.FIRING);
        assertThat(hour.detail()).contains("for 1 hour").contains("window 1 hour");
    }

    /** Sanity: o "agora" fixo é o mesmo usado nos recortes dos testes. */
    @Test
    void contexto_usa_o_instante_fixo_do_ciclo() {
        Instant now = ctx(ServiceStatus.OPERATIONAL, null).now();
        assertThat(now).isEqualTo(NOW);
        assertThat(metrics.serviceCalls)
                .as("SERVICE_STATUS nunca toca a janela de métrica")
                .isZero();
    }

    /** Threshold numérico nunca é usado neste tipo — garante semântica pura. */
    @Test
    void threshold_numerico_eh_ignorado_em_service_status() {
        AlertRule rule = new AlertRule(
                AlertingFixtures.service(),
                "Status with stray numeric",
                null,
                AlertRuleType.SERVICE_STATUS,
                AlertOperator.EQUAL,
                new BigDecimal("42"),
                ServiceStatus.UNAVAILABLE,
                EvaluationWindow.FIFTEEN_MINUTES,
                AlertSeverity.SEV1);

        EvaluationResult result = evaluator.evaluate(
                rule, ctx(ServiceStatus.OPERATIONAL, NOW.minusSeconds(10)));

        assertThat(result.verdict()).isEqualTo(EvaluationResult.Verdict.HEALTHY);
    }
}
