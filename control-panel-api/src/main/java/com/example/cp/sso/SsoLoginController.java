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

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/sso")
public class SsoLoginController {

    private final OrganizationRepository orgRepo;
    private final SsoProviderRepository providerRepo;

    @Value("${app.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

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
}
