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

    // --- RES-10: contrato exato do conjunto de controle ---------------------

    /**
     * RES-10: o contrato de {@link MonitorCheckRecorder#sanitizeMessage} é
     * exato sobre o que sobra de caractere de controle: o conjunto é
     * {@code Character.isISOControl(ch) && ch != '\t'}, i.e. os extremos das
     * três faixas (C0 {@code \u0000}–{@code \u001F}, o {@code \u007F} DEL e
     * C1 {@code \u0080}–{@code \u009F}) viram espaço, e só a tabulação
     * sobrevive.
     *
     * <p>Duas propriedades importam e estão travadas aqui: a substituição é
     * <b>por caractere</b> — sem colapso de espaços duplos (os cinco
     * controles do meio viram exatamente cinco espaços) — e o trim só apara
     * as pontas, nunca o interior da mensagem.</p>
     */
    @Test
    void controlCharacterBoundariesFromC0ThroughC1BecomeSpacesWhileTabSurvives() {
        String sanitized = MonitorCheckRecorder.sanitizeMessage(
                "pre\u0000\u001F\u007F\u0085\u009F\tmid\u0001post");

        // 5 controles → exatamente 5 espaços (um a um); o tab do meio fica
        assertThat(sanitized).isEqualTo("pre     \tmid post");
        assertThat(sanitized).doesNotContain(
                "\u0000", "\u001F", "\u007F", "\u0085", "\u009F", "\u0001");
        assertThat(sanitized).contains("\t");
    }

    /**
     * RES-10: o trim é só de borda. Os controles que cercam a mensagem viram
     * espaço ANTES do trim e somem junto com ele — o domínio no meio sobrevive
     * intacto.
     */
    @Test
    void controlCharactersAtBothEdgesAreTrimmedAwayLeavingTheDomainText() {
        String sanitized = MonitorCheckRecorder.sanitizeMessage(
                "\u0001  Target responded  \u001F");

        assertThat(sanitized).isEqualTo("Target responded");
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
