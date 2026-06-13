package com.example.cp.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ObservabilityConfig.MetricsService}, the named-counter/gauge INFRASTRUCTURE
 * the critical-flow owners increment (#26). Verifies counters register lazily and accumulate against
 * the live registry, the backlog gauge reflects the latest set value, and the no-registry fallback
 * (slice tests with no actuator metrics) silently no-ops rather than NPEing.
 */
class MetricsServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> providerFor(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Test
    @DisplayName("increments a named counter against the wired registry")
    void incrementsNamedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityConfig.MetricsService metrics = new ObservabilityConfig.MetricsService(providerFor(registry));

        metrics.increment(ObservabilityConfig.MetricsService.LOGIN_FAILURES);
        metrics.increment(ObservabilityConfig.MetricsService.LOGIN_FAILURES);
        metrics.increment(ObservabilityConfig.MetricsService.WEBHOOK_FAILURES, 3.0);

        assertThat(registry.get(ObservabilityConfig.MetricsService.LOGIN_FAILURES).counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(ObservabilityConfig.MetricsService.WEBHOOK_FAILURES).counter().count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("the outbox-backlog gauge reflects the last set value")
    void backlogGaugeTracksSetValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityConfig.MetricsService metrics = new ObservabilityConfig.MetricsService(providerFor(registry));

        metrics.setOutboxBacklog(42L);

        assertThat(registry.get(ObservabilityConfig.MetricsService.OUTBOX_BACKLOG).gauge().value())
                .isEqualTo(42.0);
    }

    @Test
    @DisplayName("falls back to a no-op registry when none is available (slice tests)")
    void noopWhenNoRegistry() {
        ObservabilityConfig.MetricsService metrics =
                new ObservabilityConfig.MetricsService(providerFor(null));

        // No registry present: increments and gauge updates must be accepted and silently discarded.
        assertThatCode(() -> {
            metrics.increment(ObservabilityConfig.MetricsService.LICENSES_ISSUED);
            metrics.increment(ObservabilityConfig.MetricsService.REDIS_FALLBACKS, 5.0);
            metrics.setOutboxBacklog(7L);
        }).doesNotThrowAnyException();
    }
}
