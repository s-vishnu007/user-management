package com.example.licenseverifier.spring;

import com.example.licenseverifier.LicenseVerifier;
import com.example.licenseverifier.spring.actuate.LicenseEndpoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableConfigurationProperties(LicenseProperties.class)
@EnableScheduling
public class LicenseVerifierAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LicenseVerifier licenseVerifier(LicenseProperties props) {
        LicenseVerifier.Builder builder = LicenseVerifier.builder()
                .audience(props.getAudience())
                .clockSkew(props.getClockSkew());
        if (props.getIssuer() != null && !props.getIssuer().isEmpty()) {
            builder.issuer(props.getIssuer());
        }
        if (props.getRefreshFromUrl() != null && !props.getRefreshFromUrl().isEmpty()) {
            try {
                builder.publicKeysFromUrl(new java.net.URI(props.getRefreshFromUrl()).toURL());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Invalid license.refresh-from-url: " + props.getRefreshFromUrl(), e);
            }
        } else {
            builder.publicKeysFromClasspath("/jwks.json");
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public LicenseService licenseService(LicenseVerifier verifier, LicenseProperties props) {
        LicenseService svc = new LicenseService(verifier, props);
        svc.load();
        return svc;
    }

    @Bean
    @ConditionalOnMissingBean
    public LicensePermissionDeniedAdvice licensePermissionDeniedAdvice() {
        return new LicensePermissionDeniedAdvice();
    }

    @AutoConfiguration
    @ConditionalOnClass(Aspect.class)
    static class AspectConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public RequiresPermissionAspect requiresPermissionAspect(LicenseService licenseService) {
            return new RequiresPermissionAspect(licenseService);
        }
    }

    @AutoConfiguration
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnProperty(
            prefix = "management.endpoint.license",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LicenseEndpoint licenseEndpoint(LicenseService licenseService) {
            return new LicenseEndpoint(licenseService);
        }
    }
}
