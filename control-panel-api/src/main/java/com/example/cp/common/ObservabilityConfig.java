package com.example.cp.common;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central observability wiring (#26, #62, #71).
 *
 * <ul>
 *   <li>Tags every metric exported by any {@link MeterRegistry} (in practice the Prometheus
 *       registry backing {@code /actuator/prometheus}) with a common {@code app=control-panel}
 *       tag so dashboards/alerts can slice by application across a shared metrics backend.</li>
 *   <li>Enables {@link io.micrometer.core.annotation.Timed @Timed} method timing by registering a
 *       {@link TimedAspect}; the application already enables AspectJ auto-proxying, so any bean
 *       method annotated with {@code @Timed} is automatically recorded.</li>
 *   <li>Publishes a {@link MetricsService} bean — the named-counter/gauge INFRASTRUCTURE that the
 *       critical-flow owners (outbox, webhooks, licensing, auth/lockout, Redis fallback) increment.
 *       Centralising the meter names here keeps them consistent so a single dashboard/alert rule set
 *       can reference them, and lets non-metrics contexts (slice tests with no registry) run via a
 *       no-op fallback registry.</li>
 * </ul>
 *
 * <p>Kept dependency-light: only micrometer-core (already pulled in by the actuator starter) and
 * Spring Boot actuator types are referenced. The {@code TimedAspect} bean is guarded so the context
 * still starts when no {@link MeterRegistry} is present (e.g. a slice test without actuator metrics).</p>
 */
@Configuration
public class ObservabilityConfig {

    /** Common-tag key applied to every meter. */
    static final String APP_TAG_KEY = "app";

    /** Common-tag value identifying this service. */
    static final String APP_TAG_VALUE = "control-panel";

    /**
     * Adds the {@code app=control-panel} common tag to every registry. Using a {@link MeterFilter}
     * common tag (rather than a per-meter tag) applies it uniformly to all current and future meters.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer() {
        return registry -> registry.config().meterFilter(MeterFilter.commonTags(
                io.micrometer.core.instrument.Tags.of(APP_TAG_KEY, APP_TAG_VALUE)));
    }

    /**
     * Backs {@code @Timed} method instrumentation. Created only when a {@link MeterRegistry} bean
     * exists so non-metrics contexts are unaffected.
     */
    @Bean
    @ConditionalOnClass(TimedAspect.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(TimedAspect.class)
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * The named-meter facade for critical-flow instrumentation. Always available (it falls back to a
     * no-op {@link SimpleMeterRegistry} when actuator metrics are absent), so call sites can inject and
     * increment unconditionally without a metrics-present guard.
     */
    @Bean
    @ConditionalOnMissingBean(MetricsService.class)
    public MetricsService metricsService(ObjectProvider<MeterRegistry> registryProvider) {
        return new MetricsService(registryProvider);
    }

    /**
     * Named-meter facade providing the metrics INFRASTRUCTURE for the critical flows the audit calls
     * out (#26): outbox FAILED, webhook attempts/failures, licenses issued/revoked, login
     * failures/lockouts, Redis fallbacks. The per-flow increments are wired in by the owning agents at
     * their call sites; this class only defines/owns the canonical meter names and a stable API so
     * those increments and any dashboards/alerts stay consistent.
     *
     * <p>Counters are registered lazily on first use against the live {@link MeterRegistry}; a gauge is
     * backed by an {@link AtomicLong} the owner updates via {@link #setOutboxBacklog(long)}. When no
     * actuator {@link MeterRegistry} is present (slice tests) a private {@link SimpleMeterRegistry} is
     * used so increments are accepted and discarded rather than NPEing.</p>
     */
    public static final class MetricsService {

        /** Canonical meter names. Keep in sync with dashboards/alert rules. */
        public static final String OUTBOX_FAILED = "cp.outbox.failed";
        public static final String WEBHOOK_ATTEMPTS = "cp.webhook.attempts";
        public static final String WEBHOOK_FAILURES = "cp.webhook.failures";
        public static final String LICENSES_ISSUED = "cp.licenses.issued";
        public static final String LICENSES_REVOKED = "cp.licenses.revoked";
        public static final String LOGIN_FAILURES = "cp.auth.login.failures";
        public static final String LOGIN_LOCKOUTS = "cp.auth.login.lockouts";
        public static final String REDIS_FALLBACKS = "cp.redis.fallbacks";
        public static final String OUTBOX_BACKLOG = "cp.outbox.backlog";

        private final MeterRegistry registry;
        private final AtomicLong outboxBacklog = new AtomicLong(0);

        MetricsService(ObjectProvider<MeterRegistry> registryProvider) {
            MeterRegistry provided = registryProvider != null ? registryProvider.getIfAvailable() : null;
            this.registry = provided != null ? provided : new SimpleMeterRegistry();
            // Register the backlog gauge once, bound to the AtomicLong the owner mutates.
            this.registry.gauge(OUTBOX_BACKLOG, outboxBacklog, AtomicLong::get);
        }

        /** Increments the named counter by one (lazily registering it). */
        public void increment(String meterName) {
            increment(meterName, 1.0);
        }

        /** Increments the named counter by {@code amount} (lazily registering it). */
        public void increment(String meterName, double amount) {
            counter(meterName).increment(amount);
        }

        /** Updates the outbox-backlog gauge (number of pending outbox events). */
        public void setOutboxBacklog(long value) {
            outboxBacklog.set(value);
        }

        private Counter counter(String name) {
            return Counter.builder(name).register(registry);
        }
    }
}
