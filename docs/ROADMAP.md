# Production Readiness Roadmap

This roadmap turns the enterprise-readiness audit (93 gaps) into a prioritized,
actionable backlog for the User-Management control panel — the Spring Boot service
that issues offline-verified Ed25519 JWT licenses to customer Docker apps, plus its
verifier SDKs and React admin UI.

## How to read this roadmap

Every audit gap is reproduced here as a checklist item in the form:

```
- [ ] <area>: <gap>  `[importance/effort]` (#<gap-id>)
```

- **`<gap-id>`** is the audit's stable number (`#1`..`#93`) so each item traces back
  to its full `desc/now/value` writeup in `AUDIT_gaps.txt`.
- **Importance** comes straight from the audit tag: `must` (must-have), `should`
  (should-have), `nice` (nice-to-have).
- **Effort** is the audit's sizing: `S` (small), `M` (medium), `L` (large).

## Priority tiers ↔ audit themes

The three priority tiers map 1:1 onto the audit's importance tags:

| Tier | Meaning | Audit tag | Count |
|------|---------|-----------|------:|
| **P0 — Block production** | Must close before this can ship / pass a security review | `must-have` | 33 |
| **P1 — Enterprise hardening** | Required to win and operate enterprise accounts at scale | `should-have` | 49 |
| **P2 — Product completeness & compliance** | Rounds out the product, polish, and broader market reach | `nice-to-have` | 11 |

### Theme cross-cut

The audit's recurring themes flow through all three tiers. The roadmap is ordered by
priority first; within each tier, items are grouped by these themes for execution:

- **Test coverage & CI** — the system is ~0% server-side tested with no pipeline; this
  is the single largest cluster (gaps #1-#8, #34-#38, #37 coverage, #83 static analysis)
  and underpins every other change.
- **Identity, auth & RBAC** — MFA, sessions, SSO correctness, password policy, step-up,
  IP allowlisting, delegated admin.
- **Licensing lifecycle & enforcement** — revocation that actually takes effect (CRL,
  heartbeat), seat/hardware binding, trials, renewal automation, expiry sweeps.
- **Data protection & compliance** — TLS, secrets management, GDPR/CCPA DSAR & erasure,
  retention, SIEM export, data residency, SOC2/ISO control mapping.
- **Operations & reliability** — backups/DR, horizontal scaling, Redis usage, optimistic
  locking, outbox reliability, probes, metrics, tracing, logging, graceful shutdown.
- **Billing platform** — payments, invoicing, rating, dunning, plan changes, webhooks.
- **API & contract integrity** — UI↔API shape mismatches, OpenAPI drift, idempotency,
  pagination, versioning, SDKs, developer docs.

---

## P0 — Block production (must-have)

These 33 gaps make the system unsafe or unfit to ship: no server-side tests, no CI, no
MFA, secrets in env vars, no TLS, no backups, multi-instance correctness defects, and
broken core flows (SSO UI, permission gating, revocation enforcement).

### Test coverage & CI

- [ ] Testing — control-panel-api: control-panel-api has zero automated tests despite a fully wired test harness (~120 source files untested) `[must/L]` (#1)
- [ ] Testing — auth: No tests for authentication — session token issuance/parsing, login lockout, password reset `[must/M]` (#2)
- [ ] Testing — RBAC: No tests for RBAC and method-level `@PreAuthorize` authorization enforcement `[must/M]` (#3)
- [ ] Testing — multitenancy: No multitenancy isolation tests — cross-tenant data leakage is unguarded `[must/L]` (#4)
- [ ] Testing — licensing: No tests for the license issue / download / revoke lifecycle (core product workflow) `[must/M]` (#5)
- [ ] Testing — issuer↔verifier contract: No round-trip contract test — the JWT claim schema can silently drift `[must/M]` (#6)
- [ ] Testing — keys/crypto: No tests for key generation, rotation, retirement, and JWKS publication `[must/M]` (#7)
- [ ] CI/CD — pipeline: No CI/CD pipeline — nothing runs the tests that do exist `[must/S]` (#8)

### Identity, auth & RBAC

- [ ] Auth — MFA: No multi-factor authentication (MFA/TOTP/WebAuthn) for password logins `[must/L]` (#9)
- [ ] Provisioning — SCIM: No SCIM endpoint for automated user provisioning / deprovisioning `[must/L]` (#10)
- [ ] Auth — sessions: No server-side session management — no revocation, forced logout, or concurrent-session limits `[must/M]` (#11)
- [ ] SSO — UI/API contract: SSO admin UI is wired to a different contract than the API implements (page is non-functional) `[must/M]` (#20)
- [ ] SSO — runtime registration: SSO providers load only at startup; runtime-created providers never take effect and JIT org binding is broken `[must/M]` (#21)
- [ ] RBAC — `/auth/me` shape: `/auth/me` response shape mismatch breaks all UI permission gating `[must/S]` (#22)

### Licensing lifecycle & enforcement

- [ ] Notifications — expiry: License expiry notifications and proactive alerts are entirely missing (no SMTP, no scheduled scan) `[must/M]` (#12)
- [ ] Licensing — revocation enforcement: Revocation is published but never enforced on offline clients (CRL optional and unconsumed) `[must/M]` (#13)
- [ ] Verifier SDK — CRL: Verifier SDK never checks the CRL — revoked licenses keep working until expiry `[must/M]` (#19)

### Data protection & compliance

- [ ] Privacy — DSAR export: No GDPR/CCPA data-subject export (right of access / data portability) `[must/M]` (#14)
- [ ] Privacy — erasure: No right-to-erasure / right-to-be-forgotten implementation (conflicts with immutable audit log) `[must/L]` (#15)
- [ ] Compliance — audit retention: No audit-log retention policy, archival, or purge — logs are immutable forever `[must/M]` (#16)
- [ ] Security — TLS/HTTPS: No TLS / HTTPS enforcement (plaintext app, DB, and Redis connections) `[must/S]` (#17)
- [ ] Security — secrets management: Secrets managed via plain env vars instead of a secrets manager (Vault/KMS) `[must/M]` (#18)

### Operations & reliability

- [ ] DR — backups/PITR: No database backup, point-in-time recovery, or disaster-recovery configuration `[must/M]` (#23)
- [ ] Scaling — stateful components: Stateful components (login lockout, envelope cache, outbox poller) break horizontal scaling `[must/M]` (#24)
- [ ] Ops — health probes: No liveness/readiness probe groups configured for Kubernetes `[must/S]` (#25)
- [ ] Observability — metrics: No Prometheus metrics registry (endpoint exposed but `micrometer-registry-prometheus` missing) `[must/S]` (#26)
- [ ] Multi-instance — caches/rate-limiter: Instance-local caches/rate-limiter are incorrect under multi-instance deploy; provisioned Redis unused `[must/M]` (#27)
- [ ] Data integrity — locking: No optimistic (`@Version`) or pessimistic locking on any entity — concurrent writes silently overwrite `[must/M]` (#28)
- [ ] Outbox — multi-instance safety: Outbox publisher is not multi-instance safe — duplicate publishing and lost NOTIFY messages `[must/M]` (#29)
- [ ] Observability — error logging: 500 errors are swallowed by the generic handler without logging `[must/S]` (#30)
- [ ] Data integrity — usage idempotency: Usage ingestion is not idempotent — retried requests double-count quota `[must/M]` (#31)

### Billing platform

- [ ] Billing — plan changes: No plan upgrade/downgrade or proration on subscriptions (only cancel-and-recreate) `[must/M]` (#32)
- [ ] Billing — webhooks: No outbound webhooks with signing and retries (events are internal NOTIFY only) `[must/L]` (#33)

---

## P1 — Enterprise hardening (should-have)

These 49 gaps are required to win, secure, and operate enterprise accounts at scale:
remaining test coverage, identity hardening, real licensing enforcement, data-governance
controls, observability depth, the billing platform, and API/contract integrity.

### Test coverage & CI

- [ ] Testing — usage/quota: No tests for usage ingestion and quota accounting (upsert SQL, month-boundary bucketing) `[should/M]` (#34)
- [ ] Testing — API keys: No tests for API key generation, hashing, constant-time verification, and revocation `[should/S]` (#35)
- [ ] Testing — SSO: No tests for SSO (OIDC/SAML) provider config and login-success handling `[should/M]` (#36)
- [ ] CI/CD — coverage gate: No code coverage measurement or enforced coverage gate (no JaCoCo/Sonar) `[should/S]` (#37)
- [ ] Testing — security regression: No security regression tests for known JWT/crypto attack classes (alg-none, wrong-key, tampering, key non-leak) `[should/M]` (#38)

### Identity, auth & RBAC

- [ ] Auth — password policy: No enforced password policy, rotation, history, or breached-password checks `[should/M]` (#39)
- [ ] SSO — JIT controls: JIT provisioning has no domain allowlist, no group-to-role mapping, no deprovisioning `[should/M]` (#40)
- [ ] Auth — step-up: No admin step-up / re-authentication for sensitive operations (no sudo mode / `acr`/`auth_time`) `[should/M]` (#41)
- [ ] Auth — Redis rate-limiting: Redis configured/deployed but unused; login rate-limiting is in-memory and not cluster-safe `[should/M]` (#60)
- [ ] Auth — reset-token flag: Reset-token exposure flag read from a JVM system property, not the documented Spring property; reset flow unusable `[should/S]` (#61)

### Licensing lifecycle & enforcement

- [ ] Licensing — heartbeat/lease: License lease / online heartbeat — schema columns exist but are dead code (no phone-home) `[should/L]` (#42)
- [ ] Licensing — seat enforcement: Seat / node activation counting and enforcement (seats are purely advisory) `[should/L]` (#43)
- [ ] Licensing — license models: No floating vs node-locked licensing models (only free-floating bearer file) `[should/L]` (#44)
- [ ] Licensing — hardware binding: No hardware / machine binding (fingerprint-locked licenses); files are fully portable `[should/L]` (#45)
- [ ] Licensing — trials: No first-class trial / temporary license type with conversion workflow `[should/M]` (#46)
- [ ] Licensing — renewal automation: No renewal automation or license re-issuance on subscription change (and no auto-revoke on cancel) `[should/M]` (#47)
- [ ] Licensing — self-service portal: No customer self-service portal for licenses (admin console only) `[should/L]` (#48)
- [ ] Licensing — last_seen telemetry: `last_seen_at`/`last_seen_ip` scaffolded everywhere but never written (no phone-home) `[should/M]` (#54)
- [ ] Licensing — expiry sweeper: No scheduled lifecycle job to transition subscriptions/licenses to EXPIRED `[should/M]` (#57)
- [ ] Data integrity — download re-issue: License download silently re-issues a new license on cache miss, creating duplicate tokens `[should/S]` (#72)

### Data protection & compliance

- [ ] Compliance — SIEM export: No audit-log export / SIEM integration (Splunk, Datadog, syslog, S3) `[should/M]` (#49)
- [ ] Privacy — tenant off-boarding: No tenant (organization) data deletion / off-boarding workflow `[should/M]` (#50)
- [ ] Privacy — PII inventory: No PII inventory / data-classification / minimization (free-form audit payloads, indefinite IP retention) `[should/M]` (#51)
- [ ] Compliance — SOC2/ISO mapping: No SOC2 / ISO 27001 control mapping or compliance documentation/artifacts `[should/L]` (#52)
- [ ] Privacy — data retention: No configurable data retention for usage events and operational PII (reset tokens never purged) `[should/M]` (#53)

### Operations & reliability

- [ ] Observability — alerting/SLOs: No alerting rules, dashboards, or SLO/SLA definitions (depends on metrics) `[should/M]` (#62)
- [ ] DB — pooling/failover: No connection-pool tuning, statement/lock timeouts, or failover/read-replica config; LISTEN conn never reconnects `[should/S]` (#63)
- [ ] DB — migration safety: Liquibase rollbacks are destructive (`DROP TABLE`) or missing; no expand/contract discipline `[should/M]` (#64)
- [ ] Ops — graceful shutdown: No graceful shutdown configuration (in-flight requests/transactions cut on SIGTERM) `[should/S]` (#65)
- [ ] Event stream — unbounded query: Event stream query is unbounded — `findSince(null)` loads the entire outbox into memory `[should/S]` (#66)
- [ ] Outbox — ordering: Outbox ordering relies on wall-clock `occurred_at`, not a monotonic sequence — clock skew reorders events `[should/S]` (#67)
- [ ] Outbox — retry/DLQ: Outbox events have no retry tracking, backoff, or poison-message handling `[should/M]` (#68)
- [ ] Observability — access logging: No HTTP request/access logging (method, path, status, latency, client IP) `[should/S]` (#69)
- [ ] Observability — structured logging: Unstructured (non-JSON) logging with no logback config or MDC fields `[should/S]` (#70)
- [ ] Observability — tracing/correlation: No distributed tracing or correlation IDs across HTTP→DB→outbox→NOTIFY hops `[should/M]` (#71)

### Billing platform

- [ ] Billing — payments: No payment provider integration (Stripe/Paddle/etc.); product tracks entitlements but never money `[should/L]` (#73)
- [ ] Billing — invoicing: No invoicing / billing records (no invoice, line-item, or credit-note modeling) `[should/L]` (#74)
- [ ] Billing — usage rating: Usage metering is not connected to billing or rating (no price-per-unit, no quota enforcement) `[should/L]` (#75)
- [ ] Billing — dunning: No dunning / failed-payment recovery workflow `[should/M]` (#76)

### API & contract integrity

- [ ] Contract — usage dedup: Usage ingestion de-dup contract `(jti, event_id)` is documented but unimplemented `[should/S]` (#55)
- [ ] Contract — UI fields: UsagePage and License list UI consume fields the API never returns (broken dashboards) `[should/S]` (#56)
- [ ] Contract — UsageReporter SDK: Documented client-side UsageReporter is vapor; usage-reporting starter config keys do not exist `[should/M]` (#58)
- [ ] Contract — OpenAPI licenses: OpenAPI labels licenses as "[Planned]" though fully implemented, and omits real endpoints/params `[should/S]` (#59)
- [ ] API — versioning: No API versioning strategy beyond a hardcoded `/v1` prefix (no deprecation/sunset policy) `[should/S]` (#77)
- [ ] API — pagination: Inconsistent pagination and filtering across list endpoints (several return unbounded bare arrays) `[should/M]` (#78)
- [ ] Contract — OpenAPI drift: OpenAPI spec drifts from the implemented API (license endpoints) `[should/S]` (#79)
- [ ] SDKs — non-JVM: No client SDKs for non-JVM languages (only JVM license verifiers exist) `[should/M]` (#80)
- [ ] API — idempotency keys: No `Idempotency-Key` support on mutating endpoints (retries can duplicate subscriptions/keys/licenses) `[should/M]` (#81)
- [ ] Docs — platform API: Developer docs cover only license verification, not the platform API (no quickstart, error catalog, webhook guide) `[should/M]` (#82)

---

## P2 — Product completeness & compliance (nice-to-have)

These 11 gaps round out the product: engineering hygiene, advanced access models,
bulk/partner operations, richer offline policy, broader market reach, and cleanup of
half-built scaffolding.

### Test coverage & CI

- [ ] CI/CD — static analysis: No static analysis, linting, or formatting enforcement (Checkstyle/PMD/SpotBugs/Spotless) `[nice/S]` (#83)

### Identity, auth & RBAC

- [ ] Security — IP allowlisting: No IP allowlisting / network-based access restriction for the admin panel or API keys `[nice/M]` (#84)
- [ ] RBAC — delegated admin: Coarse delegated-admin model — only four fixed org roles, no custom roles or scoped permission delegation `[nice/M]` (#85)

### Licensing lifecycle & enforcement

- [ ] Licensing — bulk issuance: No bulk / programmatic license issuance and lifecycle API (every license issued one subscription at a time) `[nice/M]` (#86)
- [ ] Licensing — grace period: No server-side grace period or richer offline expiry handling (grace is client-side, hardcoded) `[nice/S]` (#87)

### Data protection & compliance

- [ ] Compliance — data residency: No data residency / regionalization controls (single Postgres/Redis, no region pinning) `[nice/L]` (#88)

### Operations & reliability

- [ ] Ops — container hardening: Dockerfile lacks `HEALTHCHECK` and container-aware JVM tuning `[nice/S]` (#92)

### Event stream & outbox cleanup

- [ ] Event stream — push transport: Domain event stream is poll-only / log-only; NOTIFY consumer just logs, no real subscriber transport `[nice/L]` (#91)
- [ ] Outbox — dead code: `events.OutboxRecorder` is never invoked (duplicate of the active outbox writer) `[nice/S]` (#90)
- [ ] Outbox — dual write paths: Dual outbox-write paths with differing transactional semantics create a hidden at-least-once-vs-exactly-once gap `[nice/S]` (#93)

### Contract integrity

- [ ] Docs — starter drift: Integration guide documents license-starter config/statuses that diverge from the implementation `[nice/S]` (#89)
