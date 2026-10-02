package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sentinel.alerting.AlertEngine;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base dos testes do Alert Engine (Prompt 13) — reaproveita o vocabulário de
 * regra do {@link AbstractAlertRuleIntegrationTest} e acrescenta o que os
 * testes de CICLO precisam: invocar o engine diretamente, semear checks na
 * janela do relógio congelado e ler os alertas persistidos.
 *
 * <p><b>Relógio congelado:</b> as subclasses importam
 * {@link FixedMetricsClockConfig} — o engine avalia em
 * {@code 2026-03-01T12:34:56Z} e a janela de 15 minutos vai de
 * {@code 12:19:56Z} (incluso) até {@code 12:34:56Z} (excluso). Toda semeadura
 * fica dentro deste recorte, então nenhuma asserção depende de tempo real.</p>
 *
 * <p>Como as demais bases, esta classe NÃO tem anotações Spring: cada
 * subclasse declara o seu próprio contexto (uma classe de contexto por
 * container).</p>
 */
public abstract class AbstractAlertEngineIntegrationTest extends AbstractAlertRuleIntegrationTest {

    /** Fim (exclusivo) da janela de 15 min = "agora" do relógio congelado. */
    protected static final Instant NOW = FixedMetricsClockConfig.NOW;

    /** Início (incluso) da janela canônica de 15 minutos. */
    protected static final Instant WINDOW_FROM_15M =
            NOW.minusSeconds(com.sentinel.project.alert.model.EvaluationWindow.FIFTEEN_MINUTES.seconds());

    @Autowired
    protected AlertEngine alertEngine;

    @Autowired
    protected AlertRepository alertRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // --- URLs ---------------------------------------------------------------

    /** Coleção somente-leitura de alertas disparados do serviço. */
    protected String alertsUrl(String organizationId, String projectId, String serviceId) {
        return serviceUrl(organizationId, projectId, serviceId) + "/alerts";
    }

    /** Detalhe de UM alerta dentro do serviço. */
    protected String alertUrl(
            String organizationId, String projectId, String serviceId, String alertId) {
        return alertsUrl(organizationId, projectId, serviceId) + "/" + alertId;
    }

    // --- ciclo ---------------------------------------------------------------

    /** Um ciclo de avaliação do serviço (relógio congelado do contexto). */
    protected void evaluate(String serviceId) {
        alertEngine.evaluateService(UUID.fromString(serviceId));
    }

    // --- semeadura de métrica ------------------------------------------------

    /**
     * Checks de UM monitor dentro da janela de 15 minutos — o arranjo do
     * {@code MonitorMetricsServiceTest} (HTTP status e categoria derivados do
     * outcome), mas com instantes ancorados no relógio congelado.
     *
     * @return as linhas salvas (permitem apagar/laterar entre ciclos)
     */
    protected List<MonitorCheck> seedWindow(
            String serviceId, String monitorId, CheckOutcome outcome, int count) {
        Monitor monitor = monitorOf(serviceId, monitorId);
        List<MonitorCheck> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant startedAt = NOW.minusSeconds(30 + i * 10L);
            MonitorCheck check = new MonitorCheck(
                    monitor,
                    startedAt,
                    startedAt.plusMillis(120),
                    120,
                    outcome == CheckOutcome.SUCCESS || outcome == CheckOutcome.DEGRADED_SIGNAL
                            ? 200
                            : null,
                    outcome,
                    outcome == CheckOutcome.TARGET_FAILURE
                            ? CheckFailureCategory.TIMEOUT
                            : null,
                    "seeded check");
            seeded.add(monitorCheckRepository.save(check));
        }
        return seeded;
    }

    // --- leitura de alertas --------------------------------------------------

    /** Todos os alertas do serviço, mais recente primeiro (página larga). */
    protected List<Alert> alertsOf(String serviceId) {
        return alertRepository
                .search(UUID.fromString(serviceId), null, null,
                        PageRequest.of(0, 100,
                                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))))
                .getContent();
    }

    /** Alertas abertos (FIRING) do serviço. */
    protected List<Alert> openAlertsOf(String serviceId) {
        return alertRepository.findByServiceIdAndStatus(
                UUID.fromString(serviceId), AlertStatus.FIRING);
    }

    /** Exatamente UM alerta no serviço — devolve-o (falha se houver 0 ou 2+). */
    protected Alert singleAlertOf(String serviceId) {
        List<Alert> all = alertsOf(serviceId);
        assertThat(all).as("esperava exatamente um alerta no serviço").hasSize(1);
        return all.get(0);
    }

    // --- escrita direta de estado (apenas para semear) -----------------------

    /**
     * Congela o status do serviço SEM passar pelo pipeline — escrita de TESTE
     * via SQL puro (o escritor de produção é o {@code OperationalStateEvaluator},
     * exercitado pelo E2E; aqui o ciclo SERVICE_STATUS precisa do estado pronto).
     */
    protected void forceServiceStatus(String serviceId, String status, Instant statusChangedAt) {
        jdbcTemplate.update(
                "UPDATE services SET status = ?, status_changed_at = ? WHERE id = ?",
                status, java.sql.Timestamp.from(statusChangedAt), UUID.fromString(serviceId));
    }
}
