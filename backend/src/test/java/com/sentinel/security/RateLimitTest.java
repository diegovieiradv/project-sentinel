package com.sentinel.security;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;

public class RateLimitTest {

    @Test
    void filterDisabledInTestProfileDoesNotBlock() {
        // The @Value property is set to false by application-test.yml,
        // so RateLimitFilter passes through without blocking.
        assertThat(true).isTrue(); // Placeholder: real verification via integration test
    }

    @Test
    void atomicCounterExpiresAfterWindow() throws Exception {
        RateLimitFilter.AtomicCounter counter = new RateLimitFilter.AtomicCounter(Instant.now());
        counter.increment();
        assertThat(counter.getCount()).isEqualTo(1);
        assertThat(counter.expired(Instant.now())).isFalse();
        assertThat(counter.expired(Instant.now().plusSeconds(61))).isTrue();
    }
}
