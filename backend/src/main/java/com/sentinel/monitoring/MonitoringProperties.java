package com.sentinel.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuração do motor de monitoramento (Prompt 09).
 *
 * <p>Propriedades sob {@code sentinel.monitoring.*}, com defaults que
 * funcionam sem nenhum YAML extra (o bloco em {@code application.yml} apenas
 * documenta/expõe os mesmos valores e permite override por variável de
 * ambiente):</p>
 *
 * <ul>
 *   <li>{@code enabled} — liga/desliga o agendamento das checagens. Em
 *       {@code application-test.yml} é {@code false}: os testes existentes
 *       nunca dependem de rede nem de timing, e nenhum {@code @Scheduled} é
 *       registrado no perfil de teste (ver
 *       {@link MonitoringSchedulingConfiguration}).</li>
 *   <li>{@code scheduler-delay-ms} — atraso entre ticks do
 *       {@link MonitoringScheduler} (tick curto; o custo real fica no
 *       executor, fora da thread agendada).</li>
 *   <li>{@code worker-count} — threads do pool dedicado de checagens
 *       ({@link MonitoringDispatcher}).</li>
 *   <li>{@code queue-capacity} — fila do mesmo pool; quando ela enche, o
 *       dispatch é recusado como <b>saturação</b> (nunca como falha de
 *       alvo).</li>
 * </ul>
 *
 * <p><b>Registro:</b> {@code @Component} + {@code @ConfigurationProperties} —
 * o binding (inclusive relaxed binding de {@code worker-count} →
 * {@code workerCount}) é feito pelo {@code ConfigurationPropertiesBindingPostProcessor},
 * que está ativo em qualquer contexto Spring Boot. Não há
 * {@code @EnableConfigurationProperties} a acompanhar, o que mantém a classe
 * disponível mesmo quando a configuração de agendamento está condicional.</p>
 *
 * <p><b>Não existe flag de bypass de segurança aqui:</b> nada nesta classe
 * desliga o {@code SsrfGuard} — a revalidação de alvo acontece em toda
 * execução, ativa ou não a fase de coleta.</p>
 */
@Component
@ConfigurationProperties(prefix = "sentinel.monitoring")
public class MonitoringProperties {

    /** Default: agendamento ligado (dev/prod). O perfil test zera isso. */
    private boolean enabled = true;

    /** Default: tick a cada 10s — curto o bastante para reagir, barato o bastante para rodar sempre. */
    private long schedulerDelayMs = 10_000;

    /** Default: 4 checagens em paralelo no máximo (uma por thread). */
    private int workerCount = 4;

    /** Default: 200 checagens enfileiradas além das 4 em execução. */
    private int queueCapacity = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSchedulerDelayMs() {
        return schedulerDelayMs;
    }

    public void setSchedulerDelayMs(long schedulerDelayMs) {
        this.schedulerDelayMs = schedulerDelayMs;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
