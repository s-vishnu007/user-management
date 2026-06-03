package com.example.cp.common;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
