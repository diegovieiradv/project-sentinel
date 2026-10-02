package com.sentinel.project;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.alerting.WindowMetrics;
import com.sentinel.project.monitor.dto.MetricsSummary;
import com.sentinel.alerting.MonitorWindowMetrics;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Janela de métrica que pode EXPLODIR sob controle dos testes (Prompt 13) —
 * é como o Alert Engine enxerga uma falha de infraestrutura da fonte de
 * métrica em UM monitor específico, sem quebrar o contexto inteiro.
 *
 * <p>Arranjo espelhado do {@code FixedMetricsClockConfig}: configuração de
 * teste de nível superior, importada explicitamente APENAS pelas classes que
 * precisam do caminho de ERROR ({@code AlertNoDataTest} e
 * {@code AlertFailureIsolationTest}). O delegado é o bean real de produção —
 * quando nada está marcado, o comportamento é idêntico ao normal.</p>
 *
 * <p><b>Uso:</b> {@code FlakyWindowMetricsConfig.failingMonitorId = id;} antes
 * do ciclo e resetar em {@code @AfterEach}. Só o caminho
 * {@code monitorWindow} explode (as regras Service-level continuam normais —
 * é o que prova o isolamento POR REGRA).</p>
 */
@TestConfiguration
public class FlakyWindowMetricsConfig {

    /** Monitor cuja janela deve falhar ({@code null} = tudo normal). */
    public static volatile UUID failingMonitorId;

    @Bean
    @Primary
    WindowMetrics flakyWindowMetrics(MonitorWindowMetrics delegate) {
        return new WindowMetrics() {

            @Override
            public MetricsSummary serviceWindow(UUID serviceId, Instant from, Instant to) {
                return delegate.serviceWindow(serviceId, from, to);
            }

            @Override
            public MetricsSummary monitorWindow(UUID monitorId, Instant from, Instant to) {
                if (monitorId.equals(failingMonitorId)) {
                    throw new IllegalStateException("metrics backend down (test)");
                }
                return delegate.monitorWindow(monitorId, from, to);
            }
        };
    }
}
