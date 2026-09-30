package com.sentinel.project;

import java.time.Instant;

import com.sentinel.monitoring.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Relógio congelado dos testes de métricas (Prompt 11).
 *
 * <p>A janela de métricas é um recorte de "agora" ({@code to = floor(agora /
 * bucketSeconds) × bucketSeconds}), então um relógio real tornaria os
 * instantes de {@code from}/{@code to} e o enquadramento dos buckets
 * não-determinísticos — os testes importam esta configuração e esperam
 * valores fixos.</p>
 *
 * <p>Instante escolhido de propósito FORA de qualquer fronteira de bucket:
 * assim cada faixa demonstra o próprio truncamento ({@code 1h} → 12:30,
 * {@code 24h} → 12:00, {@code 30d} → 00:00) em vez de coincidir com ele.</p>
 */
@TestConfiguration
public class FixedMetricsClockConfig {

    /** "Agora" dos testes de métricas: 2026-03-01T12:34:56Z. */
    public static final Instant NOW = Instant.parse("2026-03-01T12:34:56Z");

    @Bean
    @Primary
    Clock fixedMetricsClock() {
        return () -> NOW;
    }
}
