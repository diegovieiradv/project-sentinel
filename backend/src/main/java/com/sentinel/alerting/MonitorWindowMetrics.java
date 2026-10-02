package com.sentinel.alerting;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.monitor.dto.MetricsSummary;
import com.sentinel.project.monitor.service.MonitorMetricsService;

import org.springframework.stereotype.Component;

/**
 * Implementação de produção do {@link WindowMetrics} (Prompt 13) — delega
 * inteiramente ao {@code MonitorMetricsService} do Prompt 11, SEM reimplementar
 * fórmula alguma (fonte única de disponibilidade/taxa/latência).
 *
 * <p><b>Sem cadeia de tenant de propósito:</b> quem chama é o motor interno,
 * já dentro do serviço resolvido — a prova Organization → Project → Service
 * acontece antes, no {@code AlertEngine}, e a leitura de métrica não é
 * endpoint público.</p>
 */
@Component
public class MonitorWindowMetrics implements WindowMetrics {

    private final MonitorMetricsService monitorMetricsService;

    public MonitorWindowMetrics(MonitorMetricsService monitorMetricsService) {
        this.monitorMetricsService = monitorMetricsService;
    }

    @Override
    public MetricsSummary serviceWindow(UUID serviceId, Instant from, Instant to) {
        return monitorMetricsService.windowSummaryByService(serviceId, from, to);
    }

    @Override
    public MetricsSummary monitorWindow(UUID monitorId, Instant from, Instant to) {
        return monitorMetricsService.windowSummaryByMonitor(monitorId, from, to);
    }
}
