package com.example.licenseverifier.spring.actuate;

import com.example.licenseverifier.License;
import com.example.licenseverifier.spring.LicenseService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

@Endpoint(id = "license")
public class LicenseEndpoint {

    private final LicenseService licenseService;

    public LicenseEndpoint(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @ReadOperation
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        LicenseService.Status status = licenseService.status();
        out.put("status", status.name());
        licenseService.currentOptional().ifPresent(license -> {
            out.put("plan", safe(license::plan));
            out.put("expiresAt", safe(license::expiresAt));
            String jti = safe(license::jti);
            if (jti != null) {
                out.put("jti", lastChars(jti, 8));
            }
            out.put("permissionsCount", safeSize(license));
            out.put("featuresCount", safeFeatureCount(license));
            out.put("kid", safe(license::keyId));
        });
        return out;
    }

    private static <T> T safe(java.util.concurrent.Callable<T> call) {
        try {
            return call.call();
        } catch (Exception ex) {
            return null;
        }
    }

    private static int safeSize(License license) {
        try {
            return license.permissions().size();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static int safeFeatureCount(License license) {
        try {
            return license.features().size();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static String lastChars(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
