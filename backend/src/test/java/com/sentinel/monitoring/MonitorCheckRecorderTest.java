package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;

import org.junit.jupiter.api.Test;

/**
 * Tradução do resultado transitório (Prompt 09) em classificação persistida
 * (Prompt 10) — unidade pura, sem Spring, sem banco.
 *
 * <p>Trava as três decisões que só existem aqui: o refinamento 3xx →
 * {@code DEGRADED_SIGNAL}, o espelho 1:1 das categorias e a higiene da
 * mensagem (trunca, nunca corta par substituto, nunca mantém caractere de
 * controle).</p>
 */
class MonitorCheckRecorderTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void twoHundredIsPersistedAsSuccessWithoutCategory() {
        MonitorExecutionResult result = MonitorExecutionResult.of(
                UUID.randomUUID(), T0, T0.plusMillis(120), 200,
                MonitorOutcome.SUCCESS, null, "Completed with HTTP 200");

        CheckOutcome outcome = MonitorCheckRecorder.toCheckOutcome(result);
        assertThat(outcome).isEqualTo(CheckOutcome.SUCCESS);
        assertThat(MonitorCheckRecorder.toFailureCategory(result, outcome)).isNull();
    }

    @Test
    void redirectIsPersistedAsDegradedSignalWithoutCategory() {
        // o Prompt 09 classifica 3xx como SUCCESS (range 200..399) — o 3xx
        // nunca é seguido (Redirect.NEVER) e aqui vira sinal de degradação
        MonitorExecutionResult result = MonitorExecutionResult.of(
                UUID.randomUUID(), T0, T0.plusMillis(80), 302,
                MonitorOutcome.SUCCESS, null, "Completed with HTTP 302");

        CheckOutcome outcome = MonitorCheckRecorder.toCheckOutcome(result);
        assertThat(outcome).isEqualTo(CheckOutcome.DEGRADED_SIGNAL);
        assertThat(MonitorCheckRecorder.toFailureCategory(result, outcome)).isNull();
    }

    @Test
    void httpErrorKeepsTargetFailureAndHttpErrorCategory() {
        MonitorExecutionResult result = MonitorExecutionResult.of(
                UUID.randomUUID(), T0, T0.plusMillis(90), 500,
                MonitorOutcome.TARGET_FAILURE, FailureCategory.HTTP_ERROR,
                "Target responded with HTTP 500");

        CheckOutcome outcome = MonitorCheckRecorder.toCheckOutcome(result);
        assertThat(outcome).isEqualTo(CheckOutcome.TARGET_FAILURE);
        assertThat(MonitorCheckRecorder.toFailureCategory(result, outcome))
                .isEqualTo(CheckFailureCategory.HTTP_ERROR);
    }

    @Test
    void networkFailureKeepsItsCategoryAndNoHttpStatus() {
        MonitorExecutionResult result = MonitorExecutionResult.of(
                UUID.randomUUID(), T0, T0.plusMillis(5000), null,
                MonitorOutcome.TARGET_FAILURE, FailureCategory.TIMEOUT,
                "Request exceeded the monitor timeout");

        CheckOutcome outcome = MonitorCheckRecorder.toCheckOutcome(result);
        assertThat(outcome).isEqualTo(CheckOutcome.TARGET_FAILURE);
        assertThat(result.httpStatus()).isEmpty();
        assertThat(MonitorCheckRecorder.toFailureCategory(result, outcome))
                .isEqualTo(CheckFailureCategory.TIMEOUT);
    }

    @Test
    void internalFailureKeepsItsInternalCategory() {
        MonitorExecutionResult result = MonitorExecutionResult.of(
                UUID.randomUUID(), T0, T0.plusMillis(2), null,
                MonitorOutcome.SENTINEL_INTERNAL_FAILURE, FailureCategory.SSRF_REVALIDATION,
                "Target rejected by SSRF revalidation");

        CheckOutcome outcome = MonitorCheckRecorder.toCheckOutcome(result);
        assertThat(outcome).isEqualTo(CheckOutcome.SENTINEL_INTERNAL_FAILURE);
        assertThat(MonitorCheckRecorder.toFailureCategory(result, outcome))
                .isEqualTo(CheckFailureCategory.SSRF_REVALIDATION);
    }

    @Test
    void controlCharactersBecomeSpacesTabsSurviveAndEdgesAreTrimmed() {
        // \r, \n e NUL viram espaço (a mensagem não pode conter caractere de
        // controle), o TAB é preservado e só as pontas são aparadas — não há
        // colapso de espaços duplos no meio (mensagem de domínio, não HTML)
        String sanitized = MonitorCheckRecorder.sanitizeMessage(
                "  Target\r\nresponded\u0000 with HTTP 500\tand more  ");

        assertThat(sanitized).isEqualTo("Target  responded  with HTTP 500\tand more");
        assertThat(sanitized).doesNotContain("\r", "\n", "\u0000");
    }

    @Test
    void messageIsTruncatedAtFiveHundredCharacters() {
        String sanitized = MonitorCheckRecorder.sanitizeMessage("x".repeat(600));

        assertThat(sanitized).hasSize(500);
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // 499 'a' + um emoji de 2 chars = 501; cortar em 500 cairia no meio
        // do par substituto e o INSERT estouraria
        String sanitized = MonitorCheckRecorder.sanitizeMessage("a".repeat(499) + "😀");

        assertThat(sanitized).isEqualTo("a".repeat(499));
    }

    @Test
    void nullMessageBecomesEmptyStringNeverNull() {
        assertThat(MonitorCheckRecorder.sanitizeMessage(null)).isEmpty();
    }

    @Test
    void recordingWithoutMonitorIdIsRejectedBeforeTouchingTheDatabase() {
        // repositórios nulos de propósito: a checagem acontece ANTES deles
        MonitorCheckRecorder recorder = new MonitorCheckRecorder(null, null);
        MonitorExecutionResult result = MonitorExecutionResult.of(
                null, T0, T0.plusMillis(1), 200, MonitorOutcome.SUCCESS, null, "ok");

        assertThatThrownBy(() -> recorder.record(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monitor id");
    }
}
