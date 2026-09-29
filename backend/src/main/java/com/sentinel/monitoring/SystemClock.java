package com.sentinel.monitoring;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * Relógio de produção: tempo real do sistema ({@link Instant#now()}).
 *
 * <p>É o único {@link Clock} registrado por padrão; testes que precisam de
 * tempo controlável publicam um {@code @Primary} no próprio contexto (mesma
 * técnica do stub de DNS do {@code MonitorSsrfTest}).</p>
 */
@Component
public class SystemClock implements Clock {

    @Override
    public Instant instant() {
        return Instant.now();
    }
}
