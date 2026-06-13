package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.plans.Plan;
import com.example.cp.plans.PlanRepository;
import com.example.cp.plans.PlanService;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionService;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LicenseClaimsBuilder {

    /**
     * Maximum license TTL in days (~100 years). Clamps {@code ttlDays}/{@code defaultTtlDays} so a
     * huge value cannot overflow {@link OffsetDateTime#plusDays(long)} into a {@code DateTimeException}
     * surfaced as a raw 500 (audit P3). 100 years is far beyond any legitimate license lifetime.
     */
    static final int MAX_TTL_DAYS = 36_500;

    /** License wire-contract claim version. Bumped if the embedded claim shape changes. */
    static final int CLAIM_VERSION = 1;

    private final OrganizationRepository orgRepo;
    private final PlanRepository planRepo;
    private final PlanService planService;
    private final SubscriptionService subService;
    private final String issuer;
    private final String defaultAudience;

    public LicenseClaimsBuilder(OrganizationRepository orgRepo,
                                PlanRepository planRepo,
                                PlanService planService,
                                SubscriptionService subService,
                                @Value("${app.signing.issuer}") String issuer,
                                @Value("${app.signing.default-audience}") String defaultAudience) {
        this.orgRepo = orgRepo;
        this.planRepo = planRepo;
        this.planService = planService;
        this.subService = subService;
        this.issuer = issuer;
        this.defaultAudience = defaultAudience;
    }

    public BuiltClaims build(Subscription sub, Integer ttlDaysOverride, List<String> audienceOverride) {
        Plan plan = planRepo.findById(sub.getPlanId())
                .orElseThrow(() -> ApiException.notFound("Plan not found for subscription"));
        Organization org = orgRepo.findById(sub.getOrgId())
                .orElseThrow(() -> ApiException.notFound("Organization not found for subscription"));

        List<String> basePerms = planService.getPermissions(plan.getId());
        Map<String, Object> baseFeatures = planService.getFeatures(plan.getId());
        SubscriptionService.ResolvedEntitlements ent = subService.resolveEntitlements(
                sub.getId(), basePerms, baseFeatures);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime exp;
        if (ttlDaysOverride != null && ttlDaysOverride > 0) {
            exp = now.plusDays(clampTtlDays(ttlDaysOverride));
        } else {
            // honour subscription end if it's sooner than plan default
            OffsetDateTime planDefault = now.plusDays(clampTtlDays(plan.getDefaultTtlDays()));
            exp = sub.getEndsAt() != null && sub.getEndsAt().isBefore(planDefault)
                    ? sub.getEndsAt()
                    : planDefault;
        }
        if (!exp.isAfter(now)) {
            throw ApiException.badRequest("Computed expiry is in the past — cannot issue license");
        }

        String jti = buildJti();
        List<String> audience = (audienceOverride != null && !audienceOverride.isEmpty())
                ? audienceOverride
                : List.of(defaultAudience);

        Map<String, Object> customer = new HashMap<>();
        customer.put("org_name", org.getName());
        customer.put("contact_email", null); // TODO: surface from org once available

        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(org.getId().toString())
                .jwtID(jti)
                .issueTime(Date.from(now.toInstant()))
                .notBeforeTime(Date.from(now.toInstant()))
                .expirationTime(Date.from(exp.toInstant()))
                .claim("subscription_id", sub.getId().toString())
                .claim("plan", plan.getCode())
                .claim("permissions", ent.permissions())
                .claim("features", ent.features())
                .claim("seats", sub.getSeats())
                .claim("customer", customer)
                .claim("version", CLAIM_VERSION);

        return new BuiltClaims(jti, now, exp, plan.getCode(), org.getName(), org.getSlug(), audience, b.build());
    }

    /**
     * Clamps a requested TTL (days) to {@code [1, MAX_TTL_DAYS]} so a pathological value cannot
     * overflow {@link OffsetDateTime#plusDays(long)} into a 500 (audit P3). A non-positive value
     * falls back to 1 day (the caller already guards {@code ttlDaysOverride > 0}; this also protects
     * a misconfigured plan {@code defaultTtlDays}).
     */
    private static int clampTtlDays(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_TTL_DAYS);
    }

    private static String buildJti() {
        // UUIDv7 hex (no dashes) prefixed for human recognisability
        String hex = Ids.newId().toString().replace("-", "");
        return "lic_" + hex;
    }

    public record BuiltClaims(
            String jti,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            String planCode,
            String orgName,
            String orgSlug,
            List<String> audience,
            JWTClaimsSet claims
    ) {
        public Instant issuedAtInstant() { return issuedAt.toInstant(); }
        public Instant expiresAtInstant() { return expiresAt.toInstant(); }
    }

    public List<String> defaultAudienceList() {
        return new ArrayList<>(List.of(defaultAudience));
    }
}
