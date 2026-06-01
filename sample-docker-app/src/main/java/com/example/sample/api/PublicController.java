package com.example.sample.api;

import com.example.licenseverifier.License;
import com.example.licenseverifier.spring.LicenseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class PublicController {

    private final ObjectProvider<LicenseService> licenseServiceProvider;

    public PublicController(ObjectProvider<LicenseService> licenseServiceProvider) {
        this.licenseServiceProvider = licenseServiceProvider;
    }

    @GetMapping("/free")
    public Map<String, Object> free() {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "ok");
        body.put("plan", currentPlan().orElse(null));
        return body;
    }

    @GetMapping("/license/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        LicenseService service = licenseServiceProvider.getIfAvailable();
        if (service == null) {
            body.put("status", "UNCONFIGURED");
            body.put("plan", null);
            body.put("expiresAt", null);
            body.put("permissions", Collections.emptyList());
            body.put("features", Collections.emptyMap());
            return body;
        }
        body.put("status", service.status().name());
        Optional<License> license = service.currentOptional();
        body.put("plan", license.map(License::getPlan).orElse(null));
        body.put("expiresAt", license.map(License::getExpiresAt).orElse(null));
        body.put("permissions", license.map(License::getPermissions).orElse(Collections.emptySet()));
        body.put("features", license.map(License::getFeatures).orElse(Collections.emptyMap()));
        body.put("seats", license.map(License::getSeats).orElse(0));
        return body;
    }

    private Optional<String> currentPlan() {
        LicenseService service = licenseServiceProvider.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }
        return service.currentOptional().map(License::getPlan);
    }
}
