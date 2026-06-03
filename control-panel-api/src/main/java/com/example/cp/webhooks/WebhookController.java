package com.example.cp.webhooks;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.keys.KeyEncryptor;
import com.example.cp.sso.UrlGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for per-org outbound webhook subscriptions. Every endpoint is gated by
 * {@code @tenantAccess.canManageOrg(#orgId)} so only super-admins or OWNER/ADMIN of the path org may
 * register/inspect/delete webhooks (API-key principals are denied writes by the checker). Single-row
 * operations additionally scope by {@code (id, org_id)} so an attacker cannot touch another tenant's
 * webhook by guessing its id.
 *
 * <p>The signing secret is generated server-side, returned exactly once in the create response, and
 * persisted only as a {@code KeyEncryptor} blob — it is never read back or echoed in any subsequent
 * DTO. The endpoint URL is validated through {@link UrlGuard} (https-only, no loopback/private/
 * link-local) before persistence to stop the webhook channel becoming an SSRF primitive.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** 32 random bytes, base64url-encoded, is the one-time HMAC secret handed to the integrator. */
    private static final int SECRET_BYTES = 32;

    private final WebhookSubscriptionRepository repo;
    private final KeyEncryptor keyEncryptor;
    private final UrlGuard urlGuard;
    private final AuditWriter auditWriter;
    private final SecureRandom rng = new SecureRandom();

    public WebhookController(WebhookSubscriptionRepository repo,
                             KeyEncryptor keyEncryptor,
                             UrlGuard urlGuard,
                             AuditWriter auditWriter) {
        this.repo = repo;
        this.keyEncryptor = keyEncryptor;
        this.urlGuard = urlGuard;
        this.auditWriter = auditWriter;
    }

    @GetMapping
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    @Transactional(readOnly = true)
    public List<WebhookDto> list(@PathVariable UUID orgId) {
        return repo.findByOrgIdOrderByCreatedAtDesc(orgId).stream().map(WebhookDto::from).toList();
    }

    @PostMapping
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    @Transactional
    public ResponseEntity<CreateResponse> create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest body) {
        // Validate the destination URL through the SSRF chokepoint BEFORE persisting anything.
        try {
            urlGuard.validate(body.url());
        } catch (UrlGuard.SsrfException e) {
            log.warn("Webhook URL rejected for org {}: {}", orgId, e.internalDetail());
            throw ApiException.badRequest(e.publicMessage());
        }

        // Generate the HMAC secret server-side; the integrator gets the plaintext exactly once.
        byte[] raw = new byte[SECRET_BYTES];
        rng.nextBytes(raw);
        String plaintextSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] secretEnc = keyEncryptor.encrypt(plaintextSecret.getBytes(StandardCharsets.UTF_8));

        String eventTypes = normalizeEventTypes(body.eventTypes());

        WebhookSubscription sub = WebhookSubscription.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .url(body.url())
                .secretEnc(secretEnc)
                .eventTypes(eventTypes)
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build();
        WebhookSubscription saved = repo.save(sub);

        // Fail-closed audit: this is an org-config change that opens an outbound data channel, so the
        // row commits atomically with the subscription. markRecorded() suppresses the interceptor dup.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("url", saved.getUrl());
        payload.put("event_types", saved.getEventTypes() == null ? "*" : saved.getEventTypes());
        auditWriter.record(actorUserId(), orgId, "webhook.subscription.created", "webhook_subscription",
                saved.getId().toString(), payload, AuditContext.currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();

        return ResponseEntity.status(201)
                .body(new CreateResponse(WebhookDto.from(saved), plaintextSecret));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@tenantAccess.canManageOrg(#orgId)")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        // Scope by (id, org_id): a missing/other-tenant row is a 404, never a cross-tenant delete.
        WebhookSubscription sub = repo.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("Webhook subscription not found"));
        repo.delete(sub);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("url", sub.getUrl());
        auditWriter.record(actorUserId(), orgId, "webhook.subscription.deleted", "webhook_subscription",
                id.toString(), payload, AuditContext.currentIp(), AuditOutcome.SUCCESS, true);
        AuditContext.markRecorded();
        return ResponseEntity.noContent().build();
    }

    /** Trim, drop blanks, and re-join the CSV; an empty/blank result becomes null (= subscribe to all). */
    private static String normalizeEventTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<String> parts = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static UUID actorUserId() {
        UUID actor = AuditContext.currentActorUserId();
        if (actor != null) return actor;
        return SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
    }

    public record CreateRequest(@NotBlank String url, String eventTypes) {}

    /** The secret is included ONLY in this create response and never again. */
    public record CreateResponse(WebhookDto webhook, String secret) {}

    public record WebhookDto(UUID id, UUID orgId, String url, String eventTypes, boolean active,
                             OffsetDateTime createdAt) {
        static WebhookDto from(WebhookSubscription s) {
            return new WebhookDto(s.getId(), s.getOrgId(), s.getUrl(), s.getEventTypes(), s.isActive(),
                    s.getCreatedAt());
        }
    }
}
