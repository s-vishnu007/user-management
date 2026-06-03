package com.example.licenseverifier.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.licenseverifier.License;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

class RequiresPermissionAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private LicenseService licenseService;
    private License license;
    private SampleService sampleService;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext();
        licenseService = Mockito.mock(LicenseService.class);
        license = Mockito.mock(License.class);
        ctx.registerBean(LicenseService.class, () -> licenseService);
        ctx.register(Config.class);
        ctx.refresh();
        sampleService = ctx.getBean(SampleService.class);
        when(licenseService.status()).thenReturn(LicenseService.Status.ACTIVE);
        when(licenseService.current()).thenReturn(license);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void allowsCallWhenPermissionPresent() {
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThat(sampleService.guarded()).isEqualTo("ok");
    }

    @Test
    void deniesCallWhenPermissionMissing() {
        when(license.hasPermission(eq("foo.bar"))).thenReturn(false);
        assertThatThrownBy(() -> sampleService.guarded())
                .isInstanceOf(LicensePermissionDeniedException.class)
                .hasMessageContaining("foo.bar");
    }

    @Test
    void anyOfMatchesWhenAtLeastOneGranted() {
        when(license.hasPermission(eq("alpha"))).thenReturn(false);
        when(license.hasPermission(eq("beta"))).thenReturn(true);
        assertThat(sampleService.anyOfGuarded()).isEqualTo("ok");
    }

    @Test
    void anyOfDeniesWhenNoneGranted() {
        when(license.hasPermission(eq("alpha"))).thenReturn(false);
        when(license.hasPermission(eq("beta"))).thenReturn(false);
        assertThatThrownBy(() -> sampleService.anyOfGuarded())
                .isInstanceOf(LicensePermissionDeniedException.class);
    }

    @Test
    void notLoadedDeniesEvenWhenLicenseMockHasPermission() {
        when(licenseService.status()).thenReturn(LicenseService.Status.NOT_LOADED);
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThatThrownBy(() -> sampleService.guarded())
                .isInstanceOf(LicensePermissionDeniedException.class)
                .hasMessageContaining("NOT_LOADED");
    }

    @Test
    void readOnlyDeniesMutatingMethod() {
        when(licenseService.status()).thenReturn(LicenseService.Status.READ_ONLY);
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThatThrownBy(() -> sampleService.guarded())
                .isInstanceOf(LicensePermissionDeniedException.class)
                .hasMessageContaining("READ_ONLY");
    }

    @Test
    void readOnlyAllowsReadOnlyAnnotatedMethod() {
        when(licenseService.status()).thenReturn(LicenseService.Status.READ_ONLY);
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThat(sampleService.readOnlyGuarded()).isEqualTo("ok");
    }

    @Test
    void revokedDeniesMutatingMethod() {
        when(licenseService.status()).thenReturn(LicenseService.Status.REVOKED);
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThatThrownBy(() -> sampleService.guarded())
                .isInstanceOf(LicensePermissionDeniedException.class)
                .hasMessageContaining("REVOKED");
    }

    @Test
    void revokedDeniesReadOnlyAnnotatedMethod() {
        when(licenseService.status()).thenReturn(LicenseService.Status.REVOKED);
        when(license.hasPermission(eq("foo.bar"))).thenReturn(true);
        assertThatThrownBy(() -> sampleService.readOnlyGuarded())
                .isInstanceOf(LicensePermissionDeniedException.class)
                .hasMessageContaining("REVOKED");
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {

        @Bean
        public RequiresPermissionAspect requiresPermissionAspect(LicenseService svc) {
            return new RequiresPermissionAspect(svc);
        }

        @Bean
        public SampleService sampleService() {
            return new SampleService();
        }
    }

    @Component
    static class SampleService {

        @RequiresPermission("foo.bar")
        public String guarded() {
            return "ok";
        }

        @RequiresPermission(anyOf = {"alpha", "beta"})
        public String anyOfGuarded() {
            return "ok";
        }

        @RequiresPermission(value = "foo.bar", readOnly = true)
        public String readOnlyGuarded() {
            return "ok";
        }
    }
}
