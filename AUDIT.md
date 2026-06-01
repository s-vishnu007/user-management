# Security & Completeness Audit — User Management Control Panel

**Audit date:** 2026-06-01
**Scope:** `control-panel-api`, `admin-ui`, `license-verifier`, `license-verifier-spring-boot-starter`, `sample-docker-app`, and supporting infrastructure (docker-compose, migrations, docs).
**System under review:** A Spring Boot 3.3 / Java 21 control panel that provisions customer subscriptions and issues offline-verifiable Ed25519 JWT licenses (`.lic` files) to customer Docker apps via a bundled verifier SDK.

---

## Executive Summary

This audit confirmed **77 findings** (each independently re-verified against source) and catalogued **93 enterprise-readiness gaps**. The architecture is sound and the cryptographic primitives (Ed25519 signing, JWKS publication, bcrypt(12) password hashing, HS256 sessions with an enforced ≥32-byte secret) are individually well-chosen. However, the system is built as a **functional skeleton**: the security model has systemic holes, the core revenue/security paths are **100% untested**, and the operational, compliance, and billing layers required for enterprise use are largely absent.

The two most serious defects strike at the product's central promises:

1. **Privilege escalation to platform super-admin** is reachable by any org admin via a single API call ([F1]).
2. **Revocation does not work.** The control panel can mark a license revoked, but no shipped verifier ever consults the revocation list, so a revoked license keeps granting full paid access until natural expiry — up to **365 days** ([F2], [F10], [F19]). For a product whose entire value proposition is *revocable* offline licensing, the revocation half of the contract is non-functional on the consumer side.

Beneath these sit a dense cluster of **cross-tenant IDOR** vulnerabilities (issue/read/revoke licenses, read/forge usage, revoke API keys, tamper subscriptions across tenant boundaries), broken session lifecycle (no revocation, no status re-check, stale authorities for 12h), an unenforced authorization model (org-scoped permissions collapse to global authorities), authenticated SSRF, and plaintext IdP secrets.

### Production-Readiness Verdict: **ALPHA**

> **Not safe for production or enterprise deployment.** The system is an internally consistent prototype demonstrating the end-to-end licensing concept, but it cannot be trusted with real tenants or real signing keys. The combination of self-service privilege escalation, pervasive cross-tenant data exposure, non-functional revocation, and zero server-side test coverage means any multi-tenant deployment is one authenticated user away from a full compromise — with no audit trail of the attack and no automated regression net to prevent re-introduction of fixed issues.
>
> Closing the P0 items below is a prerequisite for even a closed beta with trusted customers.

---

## Findings by Severity

| Severity | Count |
|----------|------:|
| CRITICAL | 2 |
| HIGH | 20 |
| MEDIUM | 30 |
| LOW | 24 |
| INFO | 1 |
| **Total** | **77** |

Plus **93 enterprise-readiness gaps** (must-have / should-have / nice-to-have), tracked separately below.

---

## CRITICAL & HIGH Findings

Full per-finding detail (description, attack scenario, verifier reasoning, file/line citations) lives in **`docs/tickets/`**. The table below is the at-a-glance index.

| ID | Sev | Title | Primary File | One-line Fix |
|----|-----|-------|--------------|--------------|
| F1 | CRITICAL | Any `user.write` holder can self-assign the `SUPER_ADMIN` role (globally) | `rbac/RbacController.java` | Gate role-assignment behind a dedicated platform authority; forbid assigning `SUPER_ADMIN`/system roles; block privilege amplification and self-elevation; validate the orgId scope. |
| F2 | CRITICAL | Revocation never enforced — verifier/starter have zero revocation logic (revoked license valid up to 365d) | `license-verifier/.../LicenseVerifier.java`, `.../spring/LicenseService.java` | Add a CRL/blocklist client to the verifier+starter that fetches revoked jtis and fails closed; drastically shorten default TTL and/or move to short-lived re-issued tokens. |
| F3 | HIGH | IDOR: any org admin can revoke any other org's API key | `apikeys/ApiKeyController.java`, `ApiKeyService.java` | Scope `revoke` to the path org (`findByIdAndOrgId`); return 404 when the key is not in the caller's org. |
| F4 | HIGH | Failed/denied security actions are never audited (login failures, lockouts, authz denials) | `audit/AuditInterceptor.java` | Add `@AfterThrowing` audit advice + explicit failure records in `AuthController.login`; add an `outcome` column. |
| F5 | HIGH | SSO login and JIT user/membership provisioning are completely unaudited | `sso/SsoSuccessHandler.java` | Inject `AuditWriter` into the success handler; record `sso.login`, `user.created`, `org.member.added` for JIT events. |
| F6 | HIGH | Append-only audit is bypassable — app connects as the audit table's owning DB role | `db/changelog/changes/08-audit.sql` | Run the app under a non-owning role with INSERT/SELECT only; keep DDL under a separate Liquibase role; ship to a WORM/SIEM sink. |
| F7 | HIGH | Session tokens can't be revoked; logout is a no-op; suspended users keep access 12h | `auth/AuthController.java`, `SessionTokenService.java`, `JwtAuthFilter.java` | Persist a jti/session record (Redis) or per-user token-version; deny on logout/suspend/reset; shorten TTL + add refresh rotation. |
| F8 | HIGH | `super_admin` & authorities trusted from token claims, never re-validated | `auth/JwtAuthFilter.java` | Reload the User and re-check `status==ACTIVE` and the current `super_admin`/permissions per request (or short cache + revocation). |
| F9 | HIGH | In-org escalation: an ADMIN can add a member as OWNER | `orgs/OrgController.java` | Enforce that the actor cannot grant a role outranking their own; require OWNER to grant OWNER. |
| F10 | HIGH | Revoked licenses remain valid — verifier never consults the CRL | `license-verifier/.../LicenseVerifier.java` | Add a `RevocationChecker` that fetches `/licenses/revoked`, caches revoked jtis, and rejects/downgrades; shorten TTLs. |
| F11 | HIGH | Subscription suspend/reactivate/cancel/overrides have no org-ownership check (cross-tenant tampering) | `subscriptions/SubscriptionController.java` | Gate every state-changing endpoint with a subscription-scoped access check resolving subId→org→membership. |
| F12 | HIGH | `SubscriptionAccess` read/download checks bypass org membership when caller holds global `subscription.read`/`license.issue` | `subscriptions/SubscriptionAccess.java` | Remove the global-authority short-circuit; always verify org membership (super-admin excepted). |
| F13 | HIGH | License issuance signs a license for ANY subscription with only global `license.issue` | `licenses/LicenseController.java` | Replace `hasAuthority('license.issue')` with `@subAccess.canIssueForSubscription(#subId)` resolving the sub's org. |
| F14 | HIGH | License read endpoints return cross-tenant tokens with no org scoping (`findAll()`) | `licenses/LicenseController.java` | Filter license queries by the caller's accessible orgs; never expose `findAll()` to non-super-admins. |
| F15 | HIGH | Usage read endpoint leaks any tenant's usage with only a global authority | `usage/UsageIngestController.java` | Resolve subId→org and require org membership (or super-admin) before returning usage. |
| F16 | HIGH | API keys can be minted with arbitrary high-priv scopes that become global authorities | `apikeys/ApiKeyAuthFilter.java`, `ApiKeyService.java` | Validate scopes against an allow-list, constrain to the owning org, and treat key scopes as org-scoped (never global) authorities. |
| F17 | HIGH | Login lockout is in-memory/per-instance, no IP/global limit → distributed brute force & spraying | `auth/LoginAttempt.java` | Move attempt accounting to the provisioned Redis; add per-IP/global counters and exponential backoff. |
| F18 | HIGH | OIDC client secret stored plaintext in JSONB and returned verbatim to any `sso.read` caller | `sso/SsoController.java`, `SsoService.java` | Encrypt secret fields at rest (KMS/envelope); redact on read; accept write-only secret updates. |
| F19 | HIGH | Authenticated SSRF via SSO "test" endpoint (OIDC issuer / SAML metadataUrl) | `sso/SsoService.java` | Enforce https-only + IP allow/deny (block loopback/RFC1918/link-local/169.254.169.254), pin resolved IP, set timeouts, disable redirects, return generic errors. |
| F20 | HIGH | Usage ingest has no tenant/ownership check — any principal can forge usage for ANY tenant | `usage/UsageIngestController.java` | Bind ingestion to the caller (verify the license bearer token, or check the principal's org against the subscription) and require `usage.write`. |
| F21 | HIGH | Negative/arbitrary quantity accepted — quota accumulator can be driven negative | `usage/UsageIngestService.java` | Validate quantity ≥ 0 (`@Positive`/`@Min`) before accumulating. |
| F22 | HIGH | No idempotency/replay protection — documented `(jti,event_id)` dedup is unimplemented | `usage/UsageIngestService.java` | Add a client `event_id` + unique constraint (or `Idempotency-Key`) and dedupe before incrementing quota. |

---

## MEDIUM & LOW Findings

- **MEDIUM (30):** API hardening (unauthenticated actuator metrics/prometheus, permissive `permitAll` catch-all, missing rate limiting, missing security/transport headers, no body-size limits), API keys never expire, audit write-failure swallowing, stale-authority 12h windows, single static KEK with no rotation, JWT in `localStorage` (XSS-exfiltratable), container runs as root, hardcoded DB/Redis creds, outdated Spring Boot 3.3.4 (known CVEs), verifier accepts no-`exp` as never-expiring, download silently re-issues on cache miss, unsigned CRL document, cross-tenant quota poisoning, nested-resource IDORs (API-key & SSO delete/test), org-scoped permissions unusable as tenant scoping, login email-enumeration timing oracle, victim-targeted lockout DoS, SSO JIT trusting unverified IdP email / no `email_verified` / subject-as-email fallback, broken STATELESS post-SSO session, SSRF via stored SAML/OIDC URLs, and unenforced `limit_value` quotas.
- **LOW (24):** issuer claim not verified on sessions, no-`exp` tokens accepted, per-instance lockout, missing function-level access control on RBAC catalog reads, low-entropy master-key SHA-256 fallback, RFC 8032 sign-bit handling in raw Ed25519 fallback, raw SQL concatenation in the NOTIFY dispatcher, 18-month retired-key trust with no kill switch, optional/unvalidated revoke reason, global audit log readable by broadly-granted `audit.read` (incl. org VIEWER), unbounded login-attempt map (memory DoS), reset-token value leakable via system property, no reset-token invalidation/rate-limit, OIDC `CLIENT_SECRET_BASIC` with defaulted empty secret / no PKCE, default `RestTemplate` (no timeouts, follows redirects), public key prefix leakage, per-request DB write on auth, spoofable `occurred_at`/login IP, and client-controlled quota period bucketing.
- **INFO (1):** Unsigned `.lic` envelope metadata is attacker-controllable and never reconciled against signed JWT claims (defense-in-depth; the verifier correctly trusts only the JWT today).

---

## Recurring Themes

These patterns connect the individual findings and should drive the remediation strategy:

1. **Authorization is gated by global authorities, not tenant scope.** Permissions resolve to flat authority strings with `orgId=null`, so `hasAuthority(...)` checks pass platform-wide. This single design flaw is the root of nearly every IDOR (F11–F16, F20) and the privilege-escalation paths (F1, F9). API-key org binding is captured but never enforced.
2. **The verifier is half a product.** Revocation, heartbeat/lease, seat enforcement, and CRL consumption all exist on the server but are dead or missing on the consumer side (F2, F10, F19; gaps G13, G42, G43, G54). Revocation is "theater."
3. **Sessions and credentials cannot be revoked or re-validated.** Stateless 12h JWTs with no blocklist, no status re-check, and baked-in authorities mean offboarding, demotion, and incident response are all delayed by up to 12 hours (F7, F8; gap G11).
4. **No tests on the security/revenue paths.** The entire `control-panel-api` module (~120 files: auth, RBAC, tenancy, licensing, keys) has **zero** test files despite a fully wired test harness, and there is no CI to run them (gaps G1–G8, G34–G38). Every fix in this report ships without a regression net.
5. **Audit trail is incomplete and tamper-capable.** Failures and SSO provisioning aren't logged, and the "immutable" log is modifiable by the app's own DB role (F4, F5, F6).
6. **Secrets & transport are unhardened.** Plaintext IdP secrets, env-var-only KEK, no TLS/HSTS, hardcoded `cp/cp` creds, root containers (F18, MEDIUM cluster; gaps G17, G18).
7. **State assumes a single instance.** In-memory lockout/cache and an unguarded outbox poller break correctness and security under horizontal scaling, even though Redis is already provisioned-but-unused (F17; gaps G24, G27, G28, G29, G60).
8. **Compliance & operations scaffolding is absent.** No MFA, SCIM, GDPR export/erasure, retention, backups/DR, metrics, probes, or webhooks (gaps G9–G33).

---

## Prioritized Roadmap

### P0 — Block the breach (before any trusted-customer deployment)

Fix the exploitable security boundary. These are mostly small, targeted code changes with outsized impact.

- **F1** — Lock down RBAC role assignment (no self-elevation to `SUPER_ADMIN`).
- **F2 / F10 / F19** — Make revocation actually work: ship CRL consumption in the verifier + starter, fail closed, and shorten default TTL.
- **F11–F16, F20** — Close every cross-tenant IDOR by resolving resource→org→membership on issue/read/revoke/usage/subscription/API-key paths; stop treating API-key scopes as global authorities.
- **F9** — Enforce rank checks on org member role grants.
- **F7 / F8** — Add server-side session revocation + per-request `status==ACTIVE` re-check.
- **F19** — Add SSRF guards (scheme/IP allowlist, IP pinning, timeouts, no redirects) to the SSO test/metadata fetch.
- **F4 / F5 / F6** — Audit failures and SSO provisioning; run the app under a least-privilege DB role.

### P1 — Harden & make enterprise-credible (before GA / first enterprise contract)

- **F17 / F18 / F21 / F22** — Distributed lockout (Redis), encrypt + redact IdP secrets, validate usage quantity, add ingestion idempotency.
- **MFA, SCIM, server-side sessions** (gaps G9, G10, G11) — table-stakes for enterprise security reviews.
- **TLS/HSTS, secrets manager, security headers, drop root containers, patch Spring Boot** (gap G17, G18; MEDIUM cluster).
- **CI + automated tests on auth/RBAC/tenancy/licensing/keys** (gaps G1–G8) — including an issuer↔verifier contract test (G6).
- **Backups/PITR/DR, liveness/readiness probes, Prometheus registry, structured logging, 500 logging** (gaps G23, G25, G26, G30, G70, G71).
- **Multi-instance correctness:** optimistic locking, outbox `FOR UPDATE SKIP LOCKED`/leader election, move caches/rate-limiter to Redis (gaps G24, G27, G28, G29).
- **Fix the broken UI ↔ API contracts** (SSO config page, `/auth/me` permission gating, runtime SSO registration/JIT org binding) (gaps G20, G21, G22).
- **GDPR/CCPA export & erasure, audit retention/archival** (gaps G14, G15, G16).

### P2 — Complete the platform (post-GA maturation)

- **License lifecycle depth:** heartbeat/lease, seat/node activation enforcement, floating vs node-locked, hardware binding, trials, renewal automation, expiry notifications (gaps G12, G42–G47, G57).
- **Billing platform:** plan upgrade/downgrade + proration, outbound signed webhooks with retries, payment provider, invoicing, usage→billing rating, dunning (gaps G32, G33, G73–G76).
- **Compliance/ops maturity:** SOC2/ISO control mapping, SIEM export, tenant offboarding, data residency, alerting/dashboards/SLOs, tracing, DB pool/failover tuning, safe migration rollbacks (gaps G49–G53, G62–G68).
- **Developer experience:** customer self-service portal, non-JVM SDKs, full platform API docs, OpenAPI/code alignment, static analysis & linting (gaps G48, G77–G83).

---

## Quick Wins

High value, low effort — start here to bank immediate progress:

- **F1** — One authorization guard removes the platform takeover path.
- **F3 / F9** — Each is a single ownership/rank check.
- **F21** — One `@Min(0)` annotation stops negative-quota corruption.
- **G30** — Add a `log.error(ex, ...)` in `GlobalExceptionHandler.handleGeneric` so 500s stop being invisible (near-zero effort).
- **G26** — Add the `micrometer-registry-prometheus` dependency to make the already-exposed `/actuator/prometheus` endpoint real.
- **G25** — Enable Boot liveness/readiness probe groups (config-only).
- **G37** — Wire JaCoCo + a coverage gate so coverage stops eroding.
- **Patch Spring Boot 3.3.4** (MEDIUM) — dependency bump closes known framework CVEs.
- **F19 (timeouts only)** — Setting connect/read timeouts and disabling redirects on the lone `RestTemplate` is a one-liner that shrinks the SSRF surface immediately.
- **G34/G35/G38** — Small, fast unit tests on the credential primitives (API-key hashing/constant-time compare, session-token alg-none/wrong-key rejection).

---

## Notes

- Every finding above was independently re-verified against the actual source (file and line citations, with mitigating nuances recorded). Severity reflects exploitability and real-world impact, not theoretical worst case — e.g., several IDORs are rated HIGH rather than CRITICAL because they require an authenticated low-privilege org member or an org-bound API key rather than an anonymous attacker.
- The full per-finding writeups — description, attack scenario, fix, and verifier reasoning for all 77 findings, plus the detailed 93-gap inventory — are maintained as individual tickets under **`docs/tickets/`**. This document is the durable executive index; consult the tickets for implementation detail.
