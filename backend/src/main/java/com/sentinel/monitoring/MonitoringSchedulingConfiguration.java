package com.sentinel.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendamento do Monitoring Engine (Prompt 09).
 *
 * <p><b>Por que existe:</b> o {@code @Scheduled} do
 * {@link MonitoringScheduler} só passa a ser registrado quando a propriedade
 * {@code sentinel.monitoring.enabled} é {@code true} (padrão: ligado — a
 * aplicação em produção monitora sem configuração extra). No perfil de
 * teste a propriedade é {@code false} em {@code application-test.yml}, o
 * que impede que QUALQUER teste de integração dispare ticks agendados por
 * acidente — os 346 testes existentes continuam sem a presença desta
 * feature. Os testes do próprio engine chamam
 * {@link MonitoringScheduler#tick()} manualmente.</p>
 *
 * <p>{@code matchIfMissing=true} mantém o comportamento produtivo mesmo sem
 * a chave no {@code application.yml} (a chave existe de qualquer forma, com
 * valores comentados/defaults documentados).</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "sentinel.monitoring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MonitoringSchedulingConfiguration {
}
