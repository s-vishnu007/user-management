# User Management Control Panel

**An offline-licensing control plane for Dockerized software products.**

`User management` is a multi-tenant SaaS back office that lets you sell, provision, and *revoke* software licenses for products that run inside your customers' own infrastructure. Admins (humans in a React console, or machines via API keys) manage **organizations**, **plans**, and **subscriptions** through a stateless Spring Boot REST API. The product it sells is **offline-verifiable licenses**: the panel mints an **Ed25519-signed JWT** (wrapped in a `.lic` file), the customer drops that file into their own Docker app, and a bundled **verifier SDK** validates it *with no network call back to the panel*. Signatures are checked against a published **JWKS**, and revocation is propagated out-of-band through a signed **CRL** — so a license can be killed even though the consuming app may be air-gapped or offline-capable.

It is built for software vendors who ship on-prem / in-customer-cloud and need the licensing guarantees of a phone-home SaaS (entitlements, seat counts, expiry, revocation) **without** requiring the customer's deployment to call home at request time.

> **Status: BETA.** The system went through a full security audit (2026-06-01, verdict *ALPHA*) and a complete remediation + re-audit (2026-06-13, verdict *BETA*). **430 tests pass** (`mvn clean verify`). See [Testing & quality](#testing--quality) and [`AUDIT.md`](AUDIT.md) / [`AUDIT-undated.md`](AUDIT-undated.md) for the full history. *Not yet hardened for fully untrusted-tenant production; intended for controlled / closed-beta multi-tenant use.*

---

## What it does

The two halves of the system **never share a database**. The only contract between the panel and a customer app is **three signed artifacts over HTTP**: the JWKS, the license file, and the CRL. Everything else is internal.

**Control plane (the panel)**
- **Multi-tenant control plane** — organizations, per-org membership roles (`OWNER > ADMIN > MEMBER > VIEWER`), users, plans, and subscriptions, all managed through a stateless REST API and a React admin console.
- **Offline Ed25519 JWT licensing** — mint, persist, distribute, seat-track, and revoke `.lic` licenses signed with rotatable Ed25519 keys. Customers verify them entirely offline against a public JWKS.
- **Revocable offline licenses** — revocation propagates through a **signed, cacheable CRL** (`typ=crl+jwt`) that the verifier fetches on a schedule and treats **fail-closed** (a stale/missing revocation view denies access rather than trusting silently). Suspending or cancelling a subscription cascades its live licenses onto the CRL.
- **RBAC + multi-tenant isolation** — a persistent roles/permissions catalog plus a single composition point (`TenantAccessChecker`, the `@tenantAccess` SpEL bean) that resolves every resource's owning org and makes cross-tenant IDOR structurally impossible. The only global bypass is `super_admin`.
- **MFA (TOTP)** — RFC-6238 two-step login (password + 6-digit code), AES-GCM-encrypted secret storage, and monotonic last-step replay protection.
- **Enterprise SSO (OIDC + SAML)** — tenant-scoped IdP config with SSRF-guarded URLs and post-login JIT provisioning that binds `(provider, subject)` to a local user.
- **SCIM 2.0 provisioning** — IdPs can auto-provision/deprovision users via an org-bound API key.
- **Programmatic API keys** — machine-to-machine auth (`Authorization: ApiKey ...`) with a scope allow-list, tenant binding, and offline-hashed verification.
- **Provider-neutral billing** — rates a subscription period's metered usage against a plan price book into dedup-guarded, optimistically-locked invoices (a `ManualBillingProvider` seam stands in for a real payment processor).
- **Usage metering** — idempotent usage-event ingestion bound to a license `jti`, with atomic, TOCTOU-safe per-(feature, month) quota enforcement.
- **Signed outbound webhooks** — a transactional outbox fans committed domain events out to per-org HTTPS subscriptions as HMAC-signed POSTs, with capped backoff retries and a per-delivery DNS-rebind SSRF re-check.
- **GDPR / CCPA data-subject rights** — secret-free data export, pseudonymising right-to-erasure, and in-transaction audit-log PII redaction.
- **Tamper-evident audit trail** — an append-only, DB-trigger-protected `audit_log` (AOP auto-capture plus explicit writes), with one narrow trigger-guarded exception for GDPR redaction.

**Customer side (the verifier)**
- **Framework-free verifier SDK** (`license-verifier`) — verifies signature, `typ`, temporal claims (`exp`/`nbf`), audience, issuer, and revocation entirely offline.
- **Drop-in Spring Boot starter** (`license-verifier-spring-boot-starter`) — auto-config, scheduled fail-closed CRL checking, an in-memory license state machine, declarative `@RequiresPermission` AOP, and an `/actuator/license` endpoint.

---

## Architecture at a glance

```
            ┌──────────────────────── CONTROL PANEL (online) ────────────────────────┐
            │  React admin UI ──HTTPS──▶ control-panel-api ──▶ PostgreSQL             │
            │     (admin-ui)                    │  ▲                                   │
            │                                   │  └── Redis (jti denylist / lockout)  │
            │            issues .lic            ▼                                      │
            │          publishes JWKS + CRL ───────────────────────────────────┐      │
            └──────────────────────────────────────────────────────────────────┼──────┘
                                                                                │ HTTP (pull)
            ┌──────────────────────── CUSTOMER APP (offline-capable) ───────────┼──────┐
            │  sample-docker-app                                                ▼      │
            │   └─ license-verifier-spring-boot-starter (auto-config + @RequiresPermission)
            │        └─ license-verifier SDK ──verifies signature, exp, aud, CRL──┘    │
            │             reads /etc/app/license.lic + cached JWKS + cached CRL        │
            └─────────────────────────────────────────────────────────────────────────┘
```

The panel holds the **private** Ed25519 signing keys (AES-GCM-encrypted at rest under a rotatable KEK) and the system-of-record database. The customer app only ever sees **public** key material (the JWKS) and a signed CRL — both fetchable from the panel — so it can verify a `.lic` file with zero calls home at request time. That is the entire trust boundary.

### Module map

| Module | Purpose | Key docs |
|---|---|---|
| **`control-panel-api`** | Spring Boot 3.3 / Java 21 REST API — issues licenses; owns orgs, RBAC, subscriptions, keys, audit, outbox, billing. | [`docs/architecture.md`](docs/architecture.md), all of [`docs/backend/`](docs/backend/), [`docs/database.md`](docs/database.md) |
| **`license-verifier`** | Plain-Java, dependency-light offline verification SDK (no Spring). Embedded into customer apps. | [`docs/modules/license-verifier.md`](docs/modules/license-verifier.md) |
| **`license-verifier-spring-boot-starter`** | Auto-config wrapping the SDK: scheduled CRL checks, `@RequiresPermission` AOP, `/actuator/license`. | [`docs/modules/starter.md`](docs/modules/starter.md) |
| **`sample-docker-app`** | Reference Spring Boot consumer showing how a customer wires the starter end-to-end. | [`docs/modules/sample-app.md`](docs/modules/sample-app.md) |
| **`admin-ui`** | React + Vite + Tailwind admin SPA (the "Aurora Glass" light-mode console; separate npm project, not a Maven module). | [`docs/frontend.md`](docs/frontend.md) + [`docs/frontend/`](docs/frontend/) |

---

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | **Java 21** (built/verified on JDK 24), **Spring Boot 3.3.13** |
| Build | Maven multi-module reactor (parent + 4 module POMs); OWASP dependency-check CVE gate |
| API | Spring Web MVC, Spring Security (stateless), springdoc-openapi (dev only) |
| Persistence | PostgreSQL 16 + **Liquibase** (formatted-SQL changelogs; Hibernate `ddl-auto=validate`) |
| Cache / coordination | **Redis 7** (session jti denylist, token-version cache, brute-force lockout counters) |
| Crypto / JWT | Nimbus JOSE+JWT + Google Tink (**Ed25519** licenses/CRL; **HS256** session/MFA tokens; **AES-GCM** KEK envelope for secrets at rest) |
| Auth | bcrypt(12) passwords, TOTP MFA (`dev.samstevens.totp`), OIDC + SAML SSO (OpenSAML via spring-security-saml2) |
| Reliability | Transactional outbox + `FOR UPDATE SKIP LOCKED` schedulers, `pg_notify`/`LISTEN`, signed webhooks |
| Observability | Micrometer + Prometheus, structured JSON logging (Logback + logstash-encoder), correlation IDs |
| Frontend | React + TypeScript + Vite + Tailwind; served by nginx in production |
| Containers / CI | Docker + docker-compose (network-segmented), GitHub Actions, Dependabot |

---

## Running locally (dev, hot reload)

1) Start Postgres + Redis:

```bash
docker run -d --name cp-pg -e POSTGRES_DB=cp -e POSTGRES_USER=cp -e POSTGRES_PASSWORD=cp -p 5432:5432 postgres:16.4
docker run -d --name cp-redis -p 6379:6379 redis:7.4
```

2) Backend (dev profile; JDK 21+ required — repo built/verified on JDK 24):

```bash
export SPRING_PROFILES_ACTIVE=dev
export APP_AUTH_COOKIE_SECURE=false                 # cp_session cookie works over http://localhost
export APP_KEY_ENC_MASTER="$(openssl rand -base64 32)"   # generate ONCE; keep STABLE (encrypts secrets at rest)
export APP_AUTH_SESSION_SECRET="$(openssl rand -base64 48)"
mvn -pl control-panel-api spring-boot:run            # API on :8080, Swagger at /swagger-ui.html (dev only)
```

3) Frontend:

```bash
cd admin-ui && npm install && npm run dev            # http://localhost:5173
```

**First login:** a dev-only bootstrap super-admin is seeded automatically under the dev profile (env: `APP_BOOTSTRAP_ADMIN_EMAIL` / `APP_BOOTSTRAP_ADMIN_PASSWORD`).

**Full docker path:** `docker compose up --build` (set `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `APP_KEY_ENC_MASTER`, `APP_AUTH_SESSION_SECRET`; add `SPRING_PROFILES_ACTIVE=dev` + `APP_AUTH_COOKIE_SECURE=false` to the api service for browser login).

**Build:** `mvn clean verify` (needs JDK 21+ and Docker for Testcontainers).

> See [`docs/operations.md`](docs/operations.md) for the complete environment-variable reference, the compose network topology, all three Dockerfiles, the nginx template, and the CI/CD setup.

---

## Repository layout

```
.
├── control-panel-api/        # The REST API server (Spring Boot 3.3 / Java 21) — issues licenses
│   └── src/main/java/com/example/cp/   # 20 feature packages (see Backend index below) + ControlPanelApplication
│   └── src/main/resources/db/changelog # Liquibase formatted-SQL migrations (00–18 + 99)
├── license-verifier/         # Framework-free offline verification SDK (plain Java JAR)
├── license-verifier-spring-boot-starter/  # Auto-config + @RequiresPermission AOP wrapping the SDK
├── sample-docker-app/        # Reference consumer app + production Dockerfile
├── admin-ui/                 # React + Vite + Tailwind admin SPA (separate npm project)
├── samples/                  # Local-only .lic / jwks.json artifacts (git-tracked via .gitkeep + README)
├── docs/                     # All deep-dive documentation (see index below)
├── docker-compose.yml        # 5 services across 2 isolated bridge networks (backend / frontend)
├── pom.xml                   # Maven reactor parent (platform + dependency mgmt + CVE gate)
├── AUDIT.md                  # Original security audit (2026-06-01, verdict ALPHA)
├── AUDIT-undated.md          # Re-audit + remediation record (2026-06-13, verdict BETA)
└── README.md                 # This file (the hub)
```

The backend lives entirely under `com.example.cp`, organized by feature package: `apikeys`, `audit`, `auth`, `billing`, `common`, `compliance`, `events`, `keys`, `licenses`, `mfa`, `orgs`, `plans`, `rbac`, `scim`, `security`, `sso`, `subscriptions`, `usage`, `users`, `webhooks`.

---

## Documentation index

The deep-dive docs under [`docs/`](docs/) are the authoritative reference. Start with the architecture narrative, then drill into the package or module you care about.

### Architecture
- [`docs/architecture.md`](docs/architecture.md) — the conceptual heart: the HTTP request lifecycle, the auth/session/MFA/SSO model, RBAC + multi-tenant isolation, end-to-end offline license issuance/verification/revocation, the transactional-outbox webhook fan-out, Ed25519 + AES-GCM KEK crypto with rotation, and idempotency/observability/graceful-shutdown.

### Backend packages (`control-panel-api`)
- [`docs/backend/auth.md`](docs/backend/auth.md) — the authentication front door: two-step (password + TOTP) login, logout, the stateless HS256 session JWT / `cp_session` cookie, Spring Security filter chains, `JwtAuthFilter`, session-revocation stores, and the brute-force lockout.
- [`docs/backend/rbac.md`](docs/backend/rbac.md) — the authorization core: persistent roles/permissions catalog, org-scoped role assignment, and the privilege-amplification / system-role guards.
- [`docs/backend/security.md`](docs/backend/security.md) — the centralized `TenantAccessChecker` (`@tenantAccess`) bean that enforces multi-tenant isolation for every resource-scoped `@PreAuthorize`.
- [`docs/backend/mfa.md`](docs/backend/mfa.md) — TOTP (RFC 6238) MFA: enrollment/confirm/verify/disable, AES-GCM-encrypted secrets, replay protection, and the short-lived MFA challenge token.
- [`docs/backend/sso.md`](docs/backend/sso.md) — enterprise SSO (OIDC + SAML): tenant-scoped provider admin, SSRF-guarded IdP URLs, and post-login JIT provisioning.
- [`docs/backend/apikeys.md`](docs/backend/apikeys.md) — programmatic API-key issuance, offline-hashed verification, and org-scoped revocation (the machine-to-machine auth path).
- [`docs/backend/orgs.md`](docs/backend/orgs.md) — the multi-tenancy backbone: organizations, membership roles, the `@orgAccess` bean, and the rank-guard + last-OWNER-protection rules.
- [`docs/backend/users.md`](docs/backend/users.md) — the system-of-record for human accounts: the `User` entity/repository and the one transactional service that enforces password policy, session revocation, auditing, and optimistic locking.
- [`docs/backend/plans.md`](docs/backend/plans.md) — the plan catalog and entitlement-template backend (permission codes + JSONB feature values).
- [`docs/backend/subscriptions.md`](docs/backend/subscriptions.md) — the subscription domain: plan binding, the lifecycle state machine, per-sub overrides, and cascade-revocation to the CRL.
- [`docs/backend/licenses.md`](docs/backend/licenses.md) — the license subsystem: mint, persist, distribute, seat-track, and revoke the offline `.lic` licenses.
- [`docs/backend/keys.md`](docs/backend/keys.md) — the cryptographic root of trust: Ed25519 key lifecycle/rotation, versioned AES-GCM KEK envelope encryption + rotation/drop-guard, and the public JWKS endpoint.
- [`docs/backend/usage.md`](docs/backend/usage.md) — usage metering: idempotent ingestion bound to a license `jti`, with atomic per-(feature, month) quota enforcement and a tenant-scoped read API.
- [`docs/backend/billing.md`](docs/backend/billing.md) — the provider-neutral billing engine: rating usage into period-scoped, dedup-guarded invoices, with a `ManualBillingProvider` seam.
- [`docs/backend/webhooks.md`](docs/backend/webhooks.md) — signed outbound webhooks: the two-phase scheduler fanning the outbox to per-org HTTPS subscriptions with backoff and DNS-rebind SSRF re-checks.
- [`docs/backend/events.md`](docs/backend/events.md) — the transactional-outbox delivery side: the multi-instance-safe `pg_notify` scheduler, the event read feed, the `LISTEN` diagnostic tail, and the retention purge.
- [`docs/backend/scim.md`](docs/backend/scim.md) — the SCIM 2.0 User provisioning surface and its per-org externalId↔user mapping table.
- [`docs/backend/compliance.md`](docs/backend/compliance.md) — GDPR/CCPA data-subject rights: export, pseudonymising erasure, off-boarding, and audit-log PII redaction.
- [`docs/backend/audit.md`](docs/backend/audit.md) — the append-only, tamper-evident audit trail: AOP auto-capture, the immutable `audit_log` table, and its RBAC-/tenant-scoped read API.
- [`docs/backend/common.md`](docs/backend/common.md) — cross-cutting infrastructure: the RFC-7807 error mapper, request-lifecycle filters (correlation ID, access log, request-size guard, rate limiting), the Idempotency-Key subsystem, non-spoofable client-IP resolution, and observability/metrics.

### Modules (customer side)
- [`docs/modules/license-verifier.md`](docs/modules/license-verifier.md) — the framework-free offline verifier SDK.
- [`docs/modules/starter.md`](docs/modules/starter.md) — the Spring Boot starter auto-configuring the SDK.
- [`docs/modules/sample-app.md`](docs/modules/sample-app.md) — the reference consumer app.

### Database
- [`docs/database.md`](docs/database.md) — the full PostgreSQL schema and Liquibase migration history: every table, constraint, index, and trigger backing tenancy/RBAC, plans/subscriptions, signing keys, license issuance, usage, billing, audit, and the outbox/webhooks — plus the audit-driven hardening waves.

### Operations
- [`docs/operations.md`](docs/operations.md) — build, configuration, and deployment: the Maven reactor, the OWASP/CVE gate, compose network isolation, all three Dockerfiles + the nginx template, GitHub Actions CI + Dependabot, every `application*.yml`/logback profile, and a complete env-var reference.

### Frontend (`admin-ui` — the "Aurora Glass" console)
- [`docs/frontend.md`](docs/frontend.md) — the frontend hub: stack, design language, how to run the SPA, the route table, and an extending-the-UI guide.
- [`docs/frontend/design-system.md`](docs/frontend/design-system.md) — the Aurora Glass design system: tokens, the glass recipe, typography (Inter/Sora), motion, and the light-mode + reduced-motion/transparency accessibility nets. (See also [`admin-ui/DESIGN_SYSTEM.md`](admin-ui/DESIGN_SYSTEM.md).)
- [`docs/frontend/components.md`](docs/frontend/components.md) — the shared `ui/` component kit and app chrome (AppShell, mobile nav, DataTable, Dialog, Tabs, …) with prop APIs and accessibility.
- [`docs/frontend/data-layer.md`](docs/frontend/data-layer.md) — the axios client, cookie-based auth bootstrap, TanStack Query wiring, and the API contract with the backend.
- [`docs/frontend/pages-core.md`](docs/frontend/pages-core.md) / [`docs/frontend/pages-ops.md`](docs/frontend/pages-ops.md) — a per-page deep-dive of all 18 screens (auth, dashboard, orgs, plans, subscriptions, licenses, keys, API keys, SSO, usage, audit).

> Additional reference material under `docs/`: [`docs/license-format.md`](docs/license-format.md) (the `.lic` envelope and JWT claim shapes), [`docs/integration-guide.md`](docs/integration-guide.md) (how a customer wires the starter), and [`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## Testing & quality

- **430 tests pass** via `mvn clean verify` (0 failures / errors / skipped), split across all four modules: License Verifier SDK (45), Control Panel API (350), Spring Boot starter (30), sample-docker-app (5).
- **Real integration tests** use **Testcontainers** (an ephemeral Postgres 16 per run via the `jdbc:tc:` URL), so `mvn clean verify` needs **JDK 21+ and a running Docker daemon**. Surefire runs unit tests; Failsafe runs `*IT` integration tests on `verify`.
- A **cross-module contract test** pulls the real `license-verifier` SDK into the API's test scope to verify the panel's live JWKS + signed CRL — proving *what we sign, the SDK can verify*.
- **CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify`, an informational dependency-update report, the admin-ui typecheck/build, and a real **OWASP CVE gate** (`-DfailBuildOnCVSS=7`) that can block a merge on any High/Critical transitive CVE. **Dependabot** keeps Maven, npm, and Actions patched weekly.
- Coverage is collected with **JaCoCo** (report-only; no enforced threshold).

### The audit → remediation history

This codebase was deliberately hardened against two security audits:

1. **[`AUDIT.md`](AUDIT.md)** (2026-06-01) — the original audit: **77 confirmed findings + 93 enterprise-readiness gaps**, verdict **ALPHA**. The two CRITICAL findings struck at the product's core promises: self-service privilege escalation to platform super-admin, and *non-functional revocation* (no shipped verifier consulted a CRL, so a revoked license stayed valid up to 365 days).
2. **[`AUDIT-undated.md`](AUDIT-undated.md)** (re-audit, remediated & verified 2026-06-13) — verdict **BETA**. All P0 (2/2) and P1 (17/17) findings, plus the P2/P3 set, were remediated by a parallel fix wave and verified by a full green `mvn clean verify` (430 tests, up from 260). Real Stripe integration, SPA CSRF hardening, multi-region, and SOC2 docs are **deliberately deferred**.

### Security highlights

The recurring design signatures across the codebase: **default-deny**, **fail-closed on security-critical paths**, **resolve fresh DB state rather than trust a token claim**, **claim work durably with `FOR UPDATE SKIP LOCKED`**, **self-proxy so `@Transactional`/`REQUIRES_NEW` actually applies**, and **never leak internals in an error body**.

- **Cross-tenant isolation is structural.** Every resource-scoped `@PreAuthorize` routes through one `TenantAccessChecker` that resolves the target's owning org and ignores authorities for resolution — the only global bypass is `super_admin`, which makes the IDOR family structurally impossible rather than convention-dependent.
- **Sessions are validated against live state.** `JwtAuthFilter` reloads the user and recomputes authorities on *every* request; three revocation channels (jti denylist, per-user token-version, account-status check) mostly fail **closed**.
- **Offline revocation fails closed.** Both the SDK and the starter deny access when the CRL is stale, missing, or rolled back; the panel caches the signed CRL and cascades subscription suspend/cancel onto it.
- **Secrets are envelope-encrypted at rest** under a rotatable AES-GCM KEK (signing keys, TOTP secrets, webhook HMAC secrets, OIDC client secrets), with a startup drop-guard that refuses to boot if a KEK rotation would orphan any secret. Passwords / API keys / reset tokens are **hashed, never stored**.
- **The audit trail is tamper-evident at the database level** — a PL/pgSQL trigger forbids any `UPDATE`/`DELETE` on `audit_log`, with one narrow GUC-gated exception for GDPR PII redaction (and `DELETE` is forbidden forever).
- **Defense at the edge** — request-size cap, per-IP token-bucket rate limiting on auth paths, non-spoofable client-IP resolution behind a trusted proxy, SSRF + DNS-rebind guards on every outbound URL (webhooks, SSO/IdP, JWKS/CRL refresh), and a minimized actuator surface locked to `SUPER_ADMIN`.

---

## License & contributing

This is a private repository. See the per-module POMs and `docs/` for internal conventions. When touching the schema, remember Liquibase owns it (`ddl-auto=validate`) — add a *new* changeset, never edit an applied one. When a CVE gate fails, the fix is usually a new SCA override property in the parent [`pom.xml`](pom.xml) (precedent: pgjdbc 42.7.7, json-smart 2.5.2, BouncyCastle 1.78.1).
