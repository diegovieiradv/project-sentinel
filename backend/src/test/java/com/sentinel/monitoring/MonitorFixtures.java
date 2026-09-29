package com.sentinel.monitoring;

import java.lang.reflect.Field;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

/**
 * Fixtures dos testes de unidade do motor (Prompt 09) — monitores
 * transitórios, nunca persistidos.
 *
 * <p>O id só nasce em {@code @PrePersist}, então os testes que não passam
 * pelo banco injetam o id por reflexão (mesma técnica já usada em outros
 * testes do projeto). Todos usam o literal IPv4 público canônico — que o
 * {@code SsrfGuard} aceita sem resolver DNS — para que os testes de SSRF
 * rodem sem Internet.</p>
 */
final class MonitorFixtures {

    /** Mesmo alvo canônico do {@code AbstractMonitorIntegrationTest.PUBLIC_URL}. */
    static final String PUBLIC_URL = "https://93.184.216.34/health";

    static final int DEFAULT_INTERVAL_SECONDS = 60;

    static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    private MonitorFixtures() {
    }

    /** Monitor transitório com id novo (útil para DueTracker/dispatcher). */
    static Monitor monitor(String url) {
        return monitor(UUID.randomUUID(), url);
    }

    /** Monitor transitório com id específico. */
    static Monitor monitor(UUID id, String url) {
        Monitor monitor = new Monitor(
                null,
                "Monitor Engine",
                url,
                MonitorMethod.GET,
                DEFAULT_INTERVAL_SECONDS,
                DEFAULT_TIMEOUT_MILLIS);
        injectId(monitor, id);
        return monitor;
    }

    /** Monitor transitório com método/intervalo/timeout customizados. */
    static Monitor monitor(
            UUID id, String url, MonitorMethod method, int intervalSeconds, int timeoutMillis) {
        Monitor monitor =
                new Monitor(null, "Monitor Engine", url, method, intervalSeconds, timeoutMillis);
        injectId(monitor, id);
        return monitor;
    }

    /**
     * O id é {@code private} e assinalado só pelo callback JPA — nos testes
     * de unidade ele é escrito por reflexão (o campo não tem setter de
     * propósito: a API nunca o expõe).
     */
    private static void injectId(Monitor monitor, UUID id) {
        try {
            Field field = Monitor.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(monitor, id);
        }
        catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot inject monitor id for tests", ex);
        }
    }
}
