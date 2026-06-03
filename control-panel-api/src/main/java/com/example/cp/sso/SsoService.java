package com.example.cp.sso;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.keys.KeyEncryptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    private final SsoProviderRepository repo;
    private final UrlGuard urlGuard;
    private final KeyEncryptor keyEncryptor;
    private final AuditWriter auditWriter;
    private final ObjectMapper mapper = new ObjectMapper();

    public SsoService(SsoProviderRepository repo, UrlGuard urlGuard, KeyEncryptor keyEncryptor,
                      AuditWriter auditWriter) {
        this.repo = repo;
        this.urlGuard = urlGuard;
        this.keyEncryptor = keyEncryptor;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public List<SsoProvider> listForOrg(UUID orgId) {
        return repo.findByOrgId(orgId);
    }

    @Transactional
    public SsoProvider create(UUID orgId, SsoProvider.Type type, Map<String, Object> config) {
        if (type == null) throw ApiException.badRequest("type is required");
        if (config == null || config.isEmpty()) throw ApiException.badRequest("config is required");

        // Work on a mutable copy so we can strip the plaintext client secret before persisting.
        Map<String, Object> cfg = new HashMap<>(config);

        // Validate the admin-supplied IdP URL through the SSRF chokepoint BEFORE saving anything.
        String url;
        if (type == SsoProvider.Type.OIDC) {
            url = asString(cfg.get("issuer"));
            if (url == null || url.isBlank()) throw ApiException.badRequest("issuer is required");
        } else {
            url = asString(cfg.get("metadataUrl"));
            if (url == null || url.isBlank()) throw ApiException.badRequest("metadataUrl is required");
        }
        try {
            urlGuard.validate(url);
        } catch (UrlGuard.SsrfException e) {
            log.warn("SSO create URL rejected for org {}: {}", orgId, e.internalDetail());
            throw ApiException.badRequest(e.publicMessage());
        }

        // Encrypt + strip the OIDC client secret so plaintext never lands in config_json or any DTO.
        byte[] clientSecretEnc = null;
        if (type == SsoProvider.Type.OIDC) {
            String secret = asString(cfg.get("clientSecret"));
            if (secret != null && !secret.isBlank()) {
                clientSecretEnc = keyEncryptor.encrypt(secret.getBytes(StandardCharsets.UTF_8));
            }
            // Remove regardless of blank/non-blank so no plaintext key remains in config_json.
            cfg.remove("clientSecret");
        }

        String allowedEmailDomains = asString(cfg.get("allowedEmailDomains"));

        String json;
        try {
            json = mapper.writeValueAsString(cfg);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid config: " + e.getMessage());
        }

        SsoProvider p = SsoProvider.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .type(type)
                .configJson(json)
                .clientSecretEnc(clientSecretEnc)
                .allowedEmailDomains(allowedEmailDomains)
                .enabled(true)
                .createdAt(OffsetDateTime.now())
                .build();
        SsoProvider saved = repo.save(p);

        // Fail-closed audit: this high-value config change is recorded INLINE in this transaction,
        // so a forensic gap is impossible (the row commits atomically with the provider). markRecorded()
        // stops the AuditInterceptor aspect from writing a duplicate row for the same request.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("type", type.name());
        auditWriter.record(actorUserId(), orgId, "sso.provider.created", "sso_provider",
                saved.getId().toString(), payload, currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        SsoProvider p = repo.findById(id).orElseThrow(() -> ApiException.notFound("SSO provider not found"));
        repo.delete(p);

        // Fail-closed audit (see create() for rationale): record inline + suppress the aspect duplicate.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", p.getType() != null ? p.getType().name() : null);
        auditWriter.record(actorUserId(), p.getOrgId(), "sso.provider.deleted", "sso_provider",
                id.toString(), payload, currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();
    }

    /** Actor for the fail-closed audit row: AuditContext (set by JwtAuthFilter) then the security context. */
    private static UUID actorUserId() {
        UUID actor = AuditContext.currentActorUserId();
        if (actor != null) return actor;
        return SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
    }

    private static String currentIp() {
        return AuditContext.currentIp();
    }

    @Transactional(readOnly = true)
    public TestResult test(UUID id) {
        SsoProvider p = repo.findById(id).orElseThrow(() -> ApiException.notFound("SSO provider not found"));
        AuditContext.set("sso.provider.tested");
        AuditContext.setTarget("sso_provider", id.toString());
        TestResult result;
        try {
            Map<String, Object> cfg = mapper.readValue(p.getConfigJson(), Map.class);
            String url;
            if (p.getType() == SsoProvider.Type.OIDC) {
                String issuer = asString(cfg.get("issuer"));
                if (issuer == null || issuer.isBlank()) {
                    result = new TestResult(false, "issuer missing in config");
                    AuditContext.putPayload("ok", false);
                    return result;
                }
                url = issuer.endsWith("/")
                        ? issuer + ".well-known/openid-configuration"
                        : issuer + "/.well-known/openid-configuration";
            } else {
                String metadataUrl = asString(cfg.get("metadataUrl"));
                if (metadataUrl == null || metadataUrl.isBlank()) {
                    result = new TestResult(false, "metadataUrl missing in config");
                    AuditContext.putPayload("ok", false);
                    return result;
                }
                url = metadataUrl;
            }
            UrlGuard.FetchResult r = urlGuard.fetchPinned(url);
            boolean ok = r.status() >= 200 && r.status() < 300;
            result = new TestResult(ok, ok ? "Identity provider reachable" : "Identity provider returned a non-success response");
        } catch (UrlGuard.SsrfException e) {
            log.warn("SSO test rejected/unreachable for id={}: {}", id, e.internalDetail());
            result = new TestResult(false, e.publicMessage());
        } catch (Exception e) {
            // Never echo internal exception text to the caller (regression guard for the old leak).
            log.warn("SSO test failed for id={}: {}", id, e.toString());
            result = new TestResult(false, "Could not test the identity provider");
        }
        AuditContext.putPayload("ok", result.ok());
        return result;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    public record TestResult(boolean ok, String message) {}
}
