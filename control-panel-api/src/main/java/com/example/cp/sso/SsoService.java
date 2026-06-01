package com.example.cp.sso;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    private final SsoProviderRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http = new RestTemplate();

    public SsoService(SsoProviderRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<SsoProvider> listForOrg(UUID orgId) {
        return repo.findByOrgId(orgId);
    }

    @Transactional
    public SsoProvider create(UUID orgId, SsoProvider.Type type, Map<String, Object> config) {
        if (type == null) throw ApiException.badRequest("type is required");
        if (config == null || config.isEmpty()) throw ApiException.badRequest("config is required");
        String json;
        try {
            json = mapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid config: " + e.getMessage());
        }
        SsoProvider p = SsoProvider.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .type(type)
                .configJson(json)
                .enabled(true)
                .createdAt(OffsetDateTime.now())
                .build();
        SsoProvider saved = repo.save(p);
        AuditContext.set("sso.provider.created");
        AuditContext.setTarget("sso_provider", saved.getId().toString());
        AuditContext.putPayload("org_id", orgId.toString());
        AuditContext.putPayload("type", type.name());
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        SsoProvider p = repo.findById(id).orElseThrow(() -> ApiException.notFound("SSO provider not found"));
        repo.delete(p);
        AuditContext.set("sso.provider.deleted");
        AuditContext.setTarget("sso_provider", id.toString());
    }

    @Transactional(readOnly = true)
    public TestResult test(UUID id) {
        SsoProvider p = repo.findById(id).orElseThrow(() -> ApiException.notFound("SSO provider not found"));
        try {
            Map<String, Object> cfg = mapper.readValue(p.getConfigJson(), Map.class);
            if (p.getType() == SsoProvider.Type.OIDC) {
                String issuer = (String) cfg.get("issuer");
                if (issuer == null) return new TestResult(false, "issuer missing in config");
                String discoveryUrl = issuer.endsWith("/") ? issuer + ".well-known/openid-configuration" : issuer + "/.well-known/openid-configuration";
                http.getForObject(discoveryUrl, String.class);
                return new TestResult(true, "OIDC discovery succeeded");
            } else {
                String metadataUrl = (String) cfg.get("metadataUrl");
                if (metadataUrl == null) return new TestResult(false, "metadataUrl missing in config");
                http.getForObject(metadataUrl, String.class);
                return new TestResult(true, "SAML metadata fetched");
            }
        } catch (Exception e) {
            log.warn("SSO test failed for id={}: {}", id, e.getMessage());
            return new TestResult(false, e.getMessage());
        }
    }

    public record TestResult(boolean ok, String message) {}
}
