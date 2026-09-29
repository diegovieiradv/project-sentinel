package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Semântica de vencimento do {@link DueTracker} (Prompt 09).
 *
 * <p>Cobre as duas correções centrais: (1) commit do próximo horário
 * <b>só</b> quando o despacho é aceito (aqui exercitado como "quem chama
 * commit é o scheduler depois do ACCEPTED") e (2) limpeza de estado
 * obsoleto via {@code retainOnly} — monitor que sai da elegibilidade perde
 * a entrada e volta a executar de imediato quando reativado.</p>
 */
class DueTrackerTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private DueTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DueTracker();
    }

    @Test
    void monitorWithoutCommitIsDueImmediately() {
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);

        assertThat(tracker.isDue(monitor, T0)).isTrue();
        assertThat(tracker.isTracked(monitor.getId())).isFalse();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void commitPostponesNextExecutionByInterval() {
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        tracker.commit(monitor, T0);

        assertThat(tracker.isTracked(monitor.getId())).isTrue();
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.nextExecutionAt(monitor.getId()))
                .isEqualTo(T0.plus(Duration.ofSeconds(MonitorFixtures.DEFAULT_INTERVAL_SECONDS)));

        // 1ms antes do intervalo: ainda não venceu
        assertThat(tracker.isDue(monitor, T0.plusSeconds(59).plusMillis(999))).isFalse();
        // exatamente no horário: vencido (inclusivo — now >= próximaExecução)
        assertThat(tracker.isDue(monitor, T0.plusSeconds(60))).isTrue();
        assertThat(tracker.isDue(monitor, T0.plusSeconds(61))).isTrue();
    }

    @Test
    void commitUsesTheCurrentIntervalOfTheMonitor() {
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        monitor.setIntervalSeconds(120);
        tracker.commit(monitor, T0);

        assertThat(tracker.nextExecutionAt(monitor.getId())).isEqualTo(T0.plusSeconds(120));
        assertThat(tracker.isDue(monitor, T0.plusSeconds(60))).isFalse();
        assertThat(tracker.isDue(monitor, T0.plusSeconds(120))).isTrue();
    }

    @Test
    void changingTheIntervalOnlyAffectsTheNextCommit() {
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        tracker.commit(monitor, T0); // +60s

        monitor.setIntervalSeconds(30);
        // sem novo commit, o horário antigo continua valendo
        assertThat(tracker.isDue(monitor, T0.plusSeconds(30))).isFalse();

        // o próximo commit passa a usar o intervalo ATUAL
        tracker.commit(monitor, T0.plusSeconds(60));
        assertThat(tracker.nextExecutionAt(monitor.getId())).isEqualTo(T0.plusSeconds(90));
    }

    @Test
    void retainOnlyDropsStateOfMonitorsThatLeftEligibility() {
        Monitor kept = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        Monitor dropped = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        tracker.commit(kept, T0);
        tracker.commit(dropped, T0);
        assertThat(tracker.size()).isEqualTo(2);

        int pruned = tracker.retainOnly(List.of(kept.getId()));

        assertThat(pruned).isEqualTo(1);
        assertThat(tracker.isTracked(kept.getId())).isTrue();
        assertThat(tracker.isTracked(dropped.getId())).isFalse();
        // o monitor remanescente mantém o horário original
        assertThat(tracker.nextExecutionAt(kept.getId())).isEqualTo(T0.plusSeconds(60));

        // quem ficou fora volta a ser "primeira execução" imediata
        assertThat(tracker.isDue(dropped, T0.plusSeconds(1))).isTrue();
    }

    @Test
    void retainOnlyWithEmptyEligibilityClearsEverything() {
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        tracker.commit(monitor, T0);

        assertThat(tracker.retainOnly(List.of())).isEqualTo(1);
        assertThat(tracker.size()).isZero();
        assertThat(tracker.isTracked(UUID.randomUUID())).isFalse();
    }
}
