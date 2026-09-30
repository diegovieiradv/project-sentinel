package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.junit.jupiter.api.Test;

/**
 * Tabela de agregação (§18) do estado operacional — unidade pura, sem
 * Spring, sem banco: só a decisão, linha a linha.
 *
 * <p>Cada teste nomeia a LINHA da tabela que trava. A evidência de cada
 * check vem do desfecho persistido: 2xx saudável, 3xx degradado, falha SEM
 * status inacessível local (ninguém respondeu), falha COM status falha
 * respondida e falha interna sem evidência.</p>
 *
 * <p><b>A linha de UNAVAILABLE é POR MONITOR</b> (correção de revisão): a
 * entrada de {@link OperationalStateEvaluator#classify} é a LISTA DE JANELAS
 * (uma por monitor ativo), nunca a concatenação delas — os testes multi-
 * monitor travam que a soma agregada não possa declarar o serviço fora.</p>
 */
class OperationalStateEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void emptyWindowHasNoEvidenceAndStaysUnknown() {
        assertThat(OperationalStateEvaluator.classify(List.of(List.of())))
                .as("monitor ativo sem nenhum check relevante")
                .isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(OperationalStateEvaluator.classify(List.of()))
                .as("nenhum monitor ativo")
                .isEqualTo(ServiceStatus.UNKNOWN);
    }

    @Test
    void windowWithOnlyInternalFailuresHasNoEvidenceAndStaysUnknown() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.SENTINEL_INTERNAL_FAILURE, null, 0),
                check(CheckOutcome.SENTINEL_INTERNAL_FAILURE, null, 1));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .as("falha do Sentinel não é evidência do alvo")
                .isEqualTo(ServiceStatus.UNKNOWN);
    }

    @Test
    void windowOfTwoHundredIsOperational() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.SUCCESS, 200, 0),
                check(CheckOutcome.SUCCESS, 200, 1),
                check(CheckOutcome.SUCCESS, 204, 2));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .isEqualTo(ServiceStatus.OPERATIONAL);
    }

    @Test
    void singleHealthyCheckIsAlreadyOperational() {
        List<MonitorCheck> window = List.of(check(CheckOutcome.SUCCESS, 200, 0));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .as("evidência positiva vale sozinha")
                .isEqualTo(ServiceStatus.OPERATIONAL);
    }

    @Test
    void threeConsecutiveUnreachableChecksAreUnavailable() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.TARGET_FAILURE, null, 0), // TIMEOUT
                check(CheckOutcome.TARGET_FAILURE, null, 1), // DNS
                check(CheckOutcome.TARGET_FAILURE, null, 2)); // CONNECT

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .as("janela cheia de inacessibilidade = serviço fora")
                .isEqualTo(ServiceStatus.UNAVAILABLE);
    }

    @Test
    void fewerThanThresholdUnreachableChecksAreDegraded() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.SUCCESS, 200, 0),
                check(CheckOutcome.TARGET_FAILURE, null, 1));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .as("uma falha isolada ainda é degradação, não indisponibilidade")
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void reachableHttpErrorsAreDegradedNeverUnavailable() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.TARGET_FAILURE, 500, 0),
                check(CheckOutcome.TARGET_FAILURE, 503, 1),
                check(CheckOutcome.TARGET_FAILURE, 500, 2));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .as("serviço responde com erro = existe, mas não funciona")
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void anyRedirectMakesTheServiceDegraded() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.SUCCESS, 200, 0),
                check(CheckOutcome.DEGRADED_SIGNAL, 302, 1),
                check(CheckOutcome.SUCCESS, 200, 2));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void mixOfHealthyAndUnreachableIsDegraded() {
        List<MonitorCheck> window = List.of(
                check(CheckOutcome.SUCCESS, 200, 0),
                check(CheckOutcome.TARGET_FAILURE, null, 1),
                check(CheckOutcome.SUCCESS, 200, 2));

        assertThat(OperationalStateEvaluator.classify(List.of(window)))
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    // --- §18 POR MONITOR: a indisponibilidade nunca é decidida sobre a
    //     janela agregada (correção de revisão — P1) ------------------------

    @Test
    void partialUnreachabilityAcrossMonitorsStaysDegraded() {
        List<List<MonitorCheck>> windows = List.of(
                List.of(unreachable(0), unreachable(1)), // A = [U, U]
                List.of(unreachable(2))); //              B = [U]

        assertThat(OperationalStateEvaluator.classify(windows))
                .as("NENHUM monitor tem 3 inacessíveis próprios: a soma agregada [U,U,U] "
                        + "declararia o serviço fora por falso-positivo")
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void oneMonitorUnavailableAndTheOtherHealthyIsDegraded() {
        List<List<MonitorCheck>> windows = List.of(
                List.of(unreachable(0), unreachable(1), unreachable(2)), // A = [U, U, U]
                List.of(healthy(0))); //                                   B = [H]

        assertThat(OperationalStateEvaluator.classify(windows))
                .as("parte do serviço responde = degradação, não indisponibilidade")
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void unavailableRequiresEvidenceFromEveryActiveMonitor() {
        List<List<MonitorCheck>> windows = List.of(
                List.of(unreachable(0), unreachable(1), unreachable(2)), // A = [U, U, U]
                List.of()); //                                            B sem nenhum check

        assertThat(OperationalStateEvaluator.classify(windows))
                .as("nem todos os monitores ativos têm evidência = DEGRADED")
                .isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void everyMonitorUnavailableIsUnavailable() {
        List<List<MonitorCheck>> windows = List.of(
                List.of(unreachable(0), unreachable(1), unreachable(2)), // A = [U, U, U]
                List.of(unreachable(0), unreachable(1), unreachable(2))); // B = [U, U, U]

        assertThat(OperationalStateEvaluator.classify(windows))
                .as("todos com evidência e todos com 3 inacessíveis próprios")
                .isEqualTo(ServiceStatus.UNAVAILABLE);
    }

    @Test
    void twoHealthyMonitorsAreOperational() {
        List<List<MonitorCheck>> windows = List.of(
                List.of(healthy(0), healthy(1)),
                List.of(healthy(0)));

        assertThat(OperationalStateEvaluator.classify(windows))
                .isEqualTo(ServiceStatus.OPERATIONAL);
    }

    @Test
    void evidenceClassificationMatchesThePersistedOutcome() {
        assertThat(OperationalStateEvaluator.evidenceOf(check(CheckOutcome.SUCCESS, 200, 0)))
                .isEqualTo(OperationalStateEvaluator.Evidence.HEALTHY);
        assertThat(OperationalStateEvaluator.evidenceOf(check(CheckOutcome.DEGRADED_SIGNAL, 301, 0)))
                .isEqualTo(OperationalStateEvaluator.Evidence.DEGRADED);
        assertThat(OperationalStateEvaluator.evidenceOf(check(CheckOutcome.TARGET_FAILURE, 502, 0)))
                .isEqualTo(OperationalStateEvaluator.Evidence.FAILING);
        assertThat(OperationalStateEvaluator.evidenceOf(check(CheckOutcome.TARGET_FAILURE, null, 0)))
                .isEqualTo(OperationalStateEvaluator.Evidence.UNAVAILABLE_LOCAL);
        assertThat(OperationalStateEvaluator.evidenceOf(
                        check(CheckOutcome.SENTINEL_INTERNAL_FAILURE, null, 0)))
                .isEqualTo(OperationalStateEvaluator.Evidence.NO_EVIDENCE);
    }

    /** Fixture: linha de histórico pronta (o monitor não importa aqui). */
    private static MonitorCheck check(CheckOutcome outcome, Integer httpStatus, int offsetSeconds) {
        Instant startedAt = T0.plusSeconds(offsetSeconds);
        return new MonitorCheck(
                null,
                startedAt,
                startedAt.plusMillis(250),
                250,
                httpStatus,
                outcome,
                null,
                "fixture");
    }

    /** Falha SEM resposta — ninguém respondeu (DNS/CONNECT/TIMEOUT/TLS). */
    private static MonitorCheck unreachable(int offsetSeconds) {
        return check(CheckOutcome.TARGET_FAILURE, null, offsetSeconds);
    }

    /** Resposta 2xx — evidência saudável. */
    private static MonitorCheck healthy(int offsetSeconds) {
        return check(CheckOutcome.SUCCESS, 200, offsetSeconds);
    }
}
