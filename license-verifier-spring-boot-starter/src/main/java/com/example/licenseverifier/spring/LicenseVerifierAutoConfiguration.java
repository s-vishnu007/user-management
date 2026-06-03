package com.example.licenseverifier.spring;

import com.example.licenseverifier.CrlVerifier;
import com.example.licenseverifier.LicenseVerifier;
import com.example.licenseverifier.PublicKeyProvider;
import com.example.licenseverifier.RevocationChecker;
import com.example.licenseverifier.spring.actuate.LicenseEndpoint;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Shared JWKS provider, built once so the {@link LicenseVerifier} and the CRL-backed
     * {@link RevocationChecker} verify against the same key set (avoids two refresh threads and
     * key-set drift right after rotation).
     */
    @Bean
    @ConditionalOnMissingBean
    public PublicKeyProvider licenseKeyProvider(LicenseProperties props) {
        if (props.getRefreshFromUrl() != null && !props.getRefreshFromUrl().isEmpty()) {
            try {
                java.net.URL url = new java.net.URI(props.getRefreshFromUrl()).toURL();
                return PublicKeyProvider.fromJwksUrl(url, props.getRefreshInterval());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Invalid license.refresh-from-url: " + props.getRefreshFromUrl(), e);
            }
        }
        try (java.io.InputStream in =
                LicenseVerifierAutoConfiguration.class.getResourceAsStream("/jwks.json")) {
            if (in == null) {
                throw new IllegalStateException("JWKS resource not found on classpath: /jwks.json");
            }
            return PublicKeyProvider.fromJwks(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load JWKS from classpath:/jwks.json", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RevocationChecker revocationChecker(
            LicenseProperties props, ObjectProvider<PublicKeyProvider> keyProvider) {
        if (props.getCrlUrl() == null || props.getCrlUrl().isBlank()) {
            return RevocationChecker.none();
        }
        CrlVerifier crl = new CrlVerifier(keyProvider.getObject(), props.getIssuer());
        CrlRevocationChecker checker = new CrlRevocationChecker(crl, props);
        checker.load();
        return checker;
    }

    @Bean
    @ConditionalOnMissingBean
    public LicenseVerifier licenseVerifier(
            LicenseProperties props,
            PublicKeyProvider keyProvider,
            RevocationChecker revocationChecker) {
        LicenseVerifier.Builder builder = LicenseVerifier.builder()
                .audience(props.getAudience())
                .clockSkew(props.getClockSkew())
                .publicKeys(keyProvider)
                .revocationChecker(revocationChecker);
        if (props.getIssuer() != null && !props.getIssuer().isEmpty()) {
            builder.issuer(props.getIssuer());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public LicenseService licenseService(
            LicenseVerifier verifier,
            LicenseProperties props,
            RevocationChecker revocationChecker) {
        LicenseService svc = new LicenseService(verifier, props, revocationChecker);
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
