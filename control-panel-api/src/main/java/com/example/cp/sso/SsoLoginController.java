package com.example.cp.sso;

import com.example.cp.common.ApiException;
import com.example.cp.orgs.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/sso")
public class SsoLoginController {

    private final OrganizationRepository orgRepo;
    private final SsoProviderRepository providerRepo;

    @Value("${app.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

    /** Mirrors {@code SsoSecurityConfig}: blank => the global Google button is hidden / disabled. */
    @Value("${app.sso.google.client-id:}")
    private String googleClientId;

    public SsoLoginController(OrganizationRepository orgRepo, SsoProviderRepository providerRepo) {
        this.orgRepo = orgRepo;
        this.providerRepo = providerRepo;
    }

    @GetMapping("/{orgSlug}/start")
    public RedirectView start(@PathVariable String orgSlug,
                              @RequestParam(required = false) String provider) {
        var org = orgRepo.findBySlug(orgSlug).orElseThrow(() -> ApiException.notFound("Organization not found"));
        List<SsoProvider> providers = providerRepo.findByOrgId(org.getId()).stream().filter(SsoProvider::isEnabled).toList();
        if (providers.isEmpty()) {
            throw ApiException.badRequest("No SSO providers configured for org");
        }
        SsoProvider selected;
        if (provider != null) {
            selected = providers.stream()
                    .filter(p -> p.getId().toString().equals(provider) || p.getType().name().equalsIgnoreCase(provider))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("SSO provider not found"));
        } else {
            selected = providers.get(0);
        }
        String registrationId = (selected.getType() == SsoProvider.Type.OIDC ? "oidc-" : "saml-") + selected.getId();
        String target = selected.getType() == SsoProvider.Type.OIDC
                ? "/oauth2/authorization/" + registrationId
                : "/saml2/authenticate/" + registrationId;
        return new RedirectView(target);
    }

    /**
     * Public discovery for the login screen. {@code q} is a work email or org slug; we resolve the org
     * by slug and return its enabled providers (id + type + label) plus the global Google flag. The SPA
     * builds the start URLs itself from {@code orgSlug}+provider id (see {@code auth.ssoStartUrl}). An
     * unknown org / no providers simply yields an empty list — it does not distinguish "no such org"
     * from "no SSO", to avoid org-enumeration via this endpoint.
     */
    @GetMapping("/discovery")
    public DiscoveryResponse discovery(@RequestParam(required = false) String q) {
        List<ProviderInfo> providers = new ArrayList<>();
        String resolvedSlug = null;
        if (q != null && !q.isBlank()) {
            // Accept either an org slug or a work email; the part after '@' is not a slug, so only the
            // bare-slug form resolves a provider list. (Domain->org mapping is intentionally omitted.)
            String slug = q.trim();
            var found = orgRepo.findBySlug(slug);
            if (found.isPresent()) {
                resolvedSlug = found.get().getSlug();
                for (SsoProvider p : providerRepo.findByOrgId(found.get().getId())) {
                    if (!p.isEnabled()) {
                        continue;
                    }
                    String label = p.getType() == SsoProvider.Type.OIDC
                            ? "Single sign-on (OIDC)"
                            : "Single sign-on (SAML)";
                    providers.add(new ProviderInfo(p.getId().toString(), p.getType().name(), label));
                }
            }
        }
        boolean googleEnabled = googleClientId != null && !googleClientId.isBlank();
        return new DiscoveryResponse(resolvedSlug, providers, googleEnabled);
    }

    /** Kicks off the global Google OIDC flow. 404 when Google credentials are not configured. */
    @GetMapping("/google/start")
    public RedirectView googleStart() {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw ApiException.notFound("Google sign-in is not configured");
        }
        return new RedirectView("/oauth2/authorization/google");
    }

    public record DiscoveryResponse(String orgSlug, List<ProviderInfo> providers, boolean googleEnabled) {}

    public record ProviderInfo(String id, String type, String label) {}
}
