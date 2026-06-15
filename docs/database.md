# Database Schema & Migrations

> **Module overview.** This document covers the entire relational schema of `control-panel-api` — the Spring Boot 3.3 / Java 21 REST service that provisions customer subscriptions and mints offline-verifiable Ed25519-signed JWT licenses. The schema is managed by **Liquibase** using the *formatted-SQL* changelog style (`--liquibase formatted sql` / `--changeset` / `--rollback` annotations). Every source file lives under `control-panel-api/src/main/resources/db/changelog/`, with a single master file (`db.changelog-master.yaml`) that `include:`s each migration in strict apply order. The schema runs on **PostgreSQL** and leans on two extensions (`pgcrypto` for `gen_random_uuid()`, `citext` for case-insensitive email). It started as a "phase-skeleton" full-system build (changesets `00`–`12` + `99`), then was hardened in waves against a security audit: the `13-*` changesets are the **P0** blockers, `14-*`/`15-*`/`16-*` are the **P1/P2** depth features (MFA, SSO hardening, outbox reliability, licensing depth, webhooks, GDPR, idempotency, SCIM, billing, key rotation), and `17-*`/`18-*` are the **optimistic-locking + integrity hardening** finishers. The application runs with `spring.jpa.hibernate.ddl-auto=validate`, so **every column here must structurally match its JPA entity** — Liquibase owns the schema, Hibernate only validates it.

> **How it fits the bigger picture.** This is the system of record for *who* (organizations, users, RBAC), *what they bought* (plans, subscriptions, overrides, billing/invoices), *what was minted* (signing keys, license tokens, the exact `.lic` artifact, activations/heartbeats), and *what happened* (audit log, outbox events, webhook deliveries, usage events, erasure log, idempotency keys). The `control-panel-api` services read/write these tables; the `license-verifier` SDK and `sample-docker-app` never touch this database — they consume the *signed JWT* and the *published JWKS* (derived from `signing_keys`) entirely offline. The audit-driven hardening migrations (`13-*` onward) are where the schema stops being a happy-path skeleton and becomes a tamper-evident, multi-instance-safe, tenant-isolated store.

---

## Table of contents

1. [Migration tooling & conventions](#1-migration-tooling--conventions)
2. [The master changelog](#2-the-master-changelog-dbchangelog-masteryaml)
3. [Foundation migrations (`00`–`12`, `99`)](#3-foundation-migrations-0012-99)
4. [P0 audit-fix wave (`13-*`)](#4-p0-audit-fix-wave-13-)
5. [P1 depth wave (`14-*`, `15-*`)](#5-p1-depth-wave-14-15-)
6. [P2 enterprise wave (`16-*`)](#6-p2-enterprise-wave-16-)
7. [Optimistic-locking & integrity finishers (`17-*`, `18-*`)](#7-optimistic-locking--integrity-finishers-17-18-)
8. [Consolidated data model](#8-consolidated-data-model)
9. [Gotchas a new engineer must know](#9-gotchas-a-new-engineer-must-know)

---

## 1. Migration tooling & conventions

| Convention | What it means here |
|---|---|
| **`--liquibase formatted sql`** | First line of every file; tells Liquibase to parse `--changeset`/`--rollback` comments instead of an XML/YAML changelog. |
| **`--changeset cp:<id>`** | Author is always `cp` (control-panel). The `<id>` is unique per changeset and is what Liquibase records in `DATABASECHANGELOG`. **Once applied, a changeset's SQL must never be edited** — change it and the checksum mismatches and the deploy fails. New changes go in a *new* changeset. |
| **`--rollback ...`** | Inline rollback SQL so `liquibase rollback` can undo a changeset. Note `cp:18-suboverride-dedupe` uses `--rollback SELECT 1;` (a no-op) because deleting duplicate rows is not reversible. |
| **`splitStatements:false`** | Used on changesets that define a PL/pgSQL function (`08-audit`, `18-webhook-fanout-integrity`). Without it, Liquibase splits on `;` and shreds the function body. |
| **`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`** | Used heavily in seed/index changesets so they are idempotent and re-runnable across the parallel-build buckets that authored them. |
| **Ordering** | Apply order is **the order of `include:` lines in the master YAML**, *not* lexical filename order. Several files share the `13-`/`18-` numeric prefix and the master file pins their relative order explicitly (see §2). |
| **`gen_random_uuid()`** | Default for almost every PK; provided by the `pgcrypto` extension created in `00-init`. |
| **`TIMESTAMPTZ` everywhere** | All timestamps are timezone-aware (`OffsetDateTime`/`Instant` on the Java side). `now()` is the standard default. |
| **Enum-as-VARCHAR + CHECK** | Status/type enums are stored as `VARCHAR` with a `CHECK (... IN (...))` constraint and mapped on the entity with `@Enumerated(EnumType.STRING)`. Widening an enum = `DROP CONSTRAINT` + re-`ADD CONSTRAINT` (see `16-keys`). |

**A note on `ddl-auto=validate`.** Because the app validates rather than generates schema, many migration files carry a comment block documenting the *exact* column→entity field mapping (e.g. `15-webhooks.sql`, `16-billing.sql`, `16-scim.sql`). If you add a column to an entity, you **must** ship a matching changeset or the Spring context will refuse to start.

---

## 2. The master changelog (`db.changelog-master.yaml`)

This is the single entry point Liquibase reads (`spring.liquibase.change-log` points here). It is a flat list of `include:` directives — no nesting, no contexts, no labels — and the **order of these lines is the canonical apply order**:

```
00-init → 01-organizations-users → 02-rbac → 03-plans → 04-subscriptions →
05-signing-keys → 06-licenses → 07-usage → 08-audit → 09-sso → 10-apikeys →
11-outbox → 12-additional-permissions →
13-session-revocation → 13-rbac-permissions → 13-usage-integrity → 13-sso-hardening → 13-audit-outcome →
14-mfa → 14-outbox-reliability →
15-licensing → 15-webhooks → 15-compliance → 15-idempotency →
16-scim → 16-billing → 16-keys →
17-optimistic-locking →
18-apikey-optimistic-lock → 18-license-token-optimistic-lock → 18-billing-invoice-integrity →
18-webhook-fanout-integrity → 18-keys-active-signing-index → 18-subscription-overrides-unique →
18-mfa-totp-replay → 18-idempotency-replay-headers →
99-auth-password-reset
```

**Why ordering matters concretely:**

- `02-rbac` must run before any later changeset that seeds `permissions`/`role_permissions` (`12`, `13-rbac`, `13-usage`, `13-sso`, `16-scim`, `16-billing`) — those inserts JOIN against the `roles`/`permissions` rows seeded in `02`.
- The five `13-*` files share a numeric prefix; the master file fixes their relative order (`session-revocation` → `rbac-permissions` → `usage-integrity` → `sso-hardening` → `audit-outcome`). Filename sort would scramble this, which is why the master YAML — not the filesystem — is authoritative.
- `99-auth-password-reset` is intentionally **last** (it depends only on `users`, and its own header notes it was retro-fitted into the master file by a different bucket). The `99` prefix signals "run after everything else regardless of when it was written."
- Several `18-*` files depend on the table they alter already existing from a `1x` file: `18-subscription-overrides-unique` needs `04`'s `subscription_overrides`, `18-license-token-optimistic-lock` needs `06`/`15`'s `license_tokens`, `18-billing-invoice-integrity` needs `16-billing`'s `invoices`, etc.

> **Gotcha:** there is no `13-additional-permissions` numbered gap problem, but note the filename `13-rbac-permissions.sql` vs `13-session-revocation.sql` etc. — they are distinct changesets that happen to share the `13-` prefix because they were authored in parallel by different "buckets" during the P0 remediation. Always trust the master YAML for order.

---

## 3. Foundation migrations (`00`–`12`, `99`)

### `changes/00-init.sql` — extensions

Enables the two PostgreSQL extensions the whole schema depends on.

| Changeset | Statement | Why |
|---|---|---|
| `cp:00-ext-pgcrypto` | `CREATE EXTENSION IF NOT EXISTS pgcrypto;` | Provides `gen_random_uuid()`, the default for nearly every primary key. |
| `cp:00-ext-citext` | `CREATE EXTENSION IF NOT EXISTS citext;` | Provides the `CITEXT` (case-insensitive text) type used for `users.email` so `Alice@x.com` and `alice@x.com` collide on the unique index. |

**Gotcha:** `CREATE EXTENSION` requires elevated DB privileges. In a least-privilege production setup (see `13-audit-outcome` header) migrations run under a privileged role while the app runs under a restricted one.

### `changes/01-organizations-users.sql` — tenancy core

The three tables that define *who exists* and *who belongs where*. This is the root of the multi-tenant model.

- **`organizations`** — the tenant. PK `id` (uuid), unique human-readable `slug` (≤64), `name`, a `status` enum (`ACTIVE`/`SUSPENDED`/`DELETED`) defaulting to `ACTIVE`, and `created_at`/`updated_at`. Index `idx_organizations_status`.
- **`users`** — a human identity, *global* (not org-scoped). `email` is `CITEXT UNIQUE NOT NULL` (one account per email platform-wide). `password_hash` is **nullable** (SSO-only users have no password). `super_admin BOOLEAN` is the platform-wide god flag. `status` enum mirrors orgs. `last_login_at` is nullable. Index `idx_users_status`.
- **`org_members`** — the M:N join binding users to orgs with a per-membership `role` (`OWNER`/`ADMIN`/`MEMBER`/`VIEWER`). Composite PK `(org_id, user_id)` so a user is in an org at most once. Both FKs `ON DELETE CASCADE` (delete the org or the user → membership vanishes). Index `idx_org_members_user` for "what orgs is this user in?" lookups.

```
organizations 1───<* org_members *>───1 users
```

**Why `super_admin` is a column here *and* a `SUPER_ADMIN` role exists (02):** the boolean is the bootstrap/break-glass flag; the role is the RBAC representation. Reconciling these two was part of the audit's "RBAC privesc to root" P0 finding.

### `changes/02-rbac.sql` — roles, permissions, and the seed catalog

Implements the RBAC engine: a catalog of `roles` and `permissions`, a `role_permissions` M:N grant table, and a `user_roles` table that can grant a role either globally or scoped to one org.

- **`roles`** — `code` (unique, e.g. `SUPER_ADMIN`), `name`, `description`, `is_system` (system roles can't be deleted/edited by tenants).
- **`permissions`** — `code` (unique, e.g. `license.issue`), `name`, `description`, `category` (UI grouping).
- **`role_permissions`** — composite PK `(role_id, permission_id)`, both FKs `ON DELETE CASCADE`. Index on `permission_id` for reverse lookups ("which roles grant X?").
- **`user_roles`** — the interesting one. Columns `user_id`, `role_id`, `org_id` (**nullable** — null = global grant). The clever bit:
  ```sql
  org_id_key UUID GENERATED ALWAYS AS
      (COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
  PRIMARY KEY (user_id, role_id, org_id_key)
  ```
  PostgreSQL treats `NULL` as distinct in a unique index, so a plain PK including the nullable `org_id` would let the *same* global grant be inserted many times. The **generated `org_id_key`** column folds `NULL` to the all-zeros sentinel UUID, making `(user, role, global)` genuinely unique. Indexes on `role_id` and `org_id`.

**Seed data (3 changesets):**
- `cp:02-roles-seed` inserts the 5 system roles: `SUPER_ADMIN`, `ORG_OWNER`, `ORG_ADMIN`, `ORG_MEMBER`, `VIEWER`.
- `cp:02-permissions-seed` inserts the initial 13 permission codes (org/subscription/license/user/audit/sso/apikey/plan families).
- `cp:02-role-permissions-seed` wires roles→permissions via `SELECT ... CROSS JOIN/JOIN`: `SUPER_ADMIN` gets **everything** (`CROSS JOIN permissions`), `ORG_OWNER` gets all-but-`plan.write`, `ORG_ADMIN` a slightly narrower set (no `org.write`, no `sso.write`), `ORG_MEMBER` only the three read perms, `VIEWER` reads + `audit.read`.

**Why the SELECT-based grant pattern:** because permission/role UUIDs are `gen_random_uuid()` and unknown at write time, every grant is expressed as a join on the stable `code` columns. This same pattern recurs in every later permission-seeding changeset.

### `changes/03-plans.sql` — the product catalog

Defines the sellable plans and what each plan grants.

- **`plans`** — `code` (unique, e.g. `pro`), `name`, `description`, `tier`, `is_active`, `default_ttl_days` (default **365** — the default license validity for subscriptions on this plan), `created_at`.
- **`plan_permissions`** — composite PK `(plan_id, permission_code)`. Note `permission_code` is a **free-text VARCHAR**, *not* an FK to `permissions.code` — these are *product-feature* permission strings (`export.csv`, `api.v2`, `admin.sso.configure`) baked into the license JWT, conceptually separate from the *RBAC* permissions in `02`.
- **`plan_features`** — composite PK `(plan_id, feature_key)`, with `value_json JSONB` so a feature value can be a number, boolean, string, or object (`max_users=50`, `ai_assistant=true`).

**Seed:** three plans (`starter`/`pro`/`enterprise`), their feature permission sets (escalating), and their feature flags (`max_users`, `max_storage_gb`, `ai_assistant`). The `VALUES ... AS t(...)` table-literal pattern keeps the seed compact.

### `changes/04-subscriptions.sql` — what an org bought

- **`subscriptions`** — binds an `org_id` to a `plan_id` for a time window (`starts_at`/`ends_at`, both `NOT NULL`). `status` enum (`ACTIVE`/`SUSPENDED`/`EXPIRED`/`CANCELLED`). Optional `seats`, free-text `notes`, `created_by` (FK to `users`, nullable — system-created subs have no creator). FKs: `org_id` cascades; `plan_id` does **not** cascade (you can't delete a plan that has subscriptions). Four indexes: `org`, `plan`, `status`, and `ends_at` (the last one powers the lifecycle sweeper that expires subs).
- **`subscription_overrides`** — per-subscription deviations from the plan baseline. `type` enum (`PERMISSION`/`FEATURE`), a `key` (≤128), and nullable `value_json`. A `PERMISSION` override adds an entitlement; a `FEATURE` override changes a feature value for *this* subscription only. Index on `subscription_id`.

> This table's lack of a uniqueness guard is later fixed by `18-subscription-overrides-unique` (entitlement resolution was order-dependent — see §7).

### `changes/05-signing-keys.sql` — the crypto root of trust

- **`signing_keys`** — the Ed25519 keypairs that sign license JWTs. `kid` (unique key id, surfaced in the JWT header and the published JWKS). `algorithm` (e.g. `EdDSA`). `public_key_pem TEXT` (published to verifiers). **`private_key_encrypted BYTEA`** — the private key, **never stored in clear**; it's AES-GCM-encrypted under a Key-Encryption-Key (KEK) by the app's `KeyEncryptor`. `status` enum starts as `ACTIVE`/`RETIRED` (later widened to add `COMPROMISED` in `16-keys`). `created_at`/`retired_at`. Index on `status`.

**Why this table is the linchpin of the whole product:** the *public* half of these keys becomes the JWKS that customer Docker apps fetch (or bundle) so they can verify `.lic` files **offline**. Rotating, retiring, or compromising a key here directly changes what verifiers will trust.

### `changes/06-licenses.sql` — issued license tokens

- **`license_tokens`** — one row per minted license. `jti` (unique JWT ID, the license's identity and CRL key). `subscription_id` FK (originally `ON DELETE CASCADE`; re-pointed to `RESTRICT` in `18` — see §7). `kid` records which signing key signed it. `issued_at`/`expires_at`. `revoked_at`/`revoke_reason` for revocation. `fingerprint` (optional binding). `last_seen_at`/`last_seen_ip` for telemetry. `status` enum (`ACTIVE`/`REVOKED`/`EXPIRED`).

  **Inline gotcha (documented in the SQL itself):** `last_seen_ip` is `VARCHAR(45)` *not* `INET` — the JPA entity maps it as a `String`, Hibernate binds it as `VARCHAR`, and a strict `inet` column would reject that bind. 45 chars fits a full IPv6 + scope.

  Indexes: `(subscription_id, status)` (list a sub's active licenses), `jti`, and `expires_at` (lifecycle expiry sweep).

**This is the revocation/CRL backbone:** offline verifiers trust any non-expired signed JWT, so *revocation* must be communicated out-of-band — the control panel exposes revoked `jti`s (a CRL) derived from this table.

### `changes/07-usage.sql` — metering

- **`usage_events`** — append-only metering facts: `subscription_id`, optional `jti`, `feature_key`, `quantity` (NUMERIC, default 1), `occurred_at`, optional `metadata_json`. Indexes `(subscription_id, occurred_at)` and `jti`.
- **`usage_quotas`** — rolling counters: composite PK `(subscription_id, feature_key, period_start)`, with `period_end`, `limit_value` (nullable = unlimited), and `consumed_value` (default 0). This is the materialized "how much of the quota is used this period" row that the rating/billing logic reads.

> Hardened by `13-usage-integrity`: dedup index, non-negative CHECKs, and `event_id` for idempotent ingest (see §4).

### `changes/08-audit.sql` — the tamper-evident security trail

- **`audit_log`** — `actor_user_id`, `actor_org_id` (both nullable — system actions have no human actor), `action` (e.g. `license.issue`), `target_type`/`target_id`, `payload_json`, `ip_address INET` (real `inet` here, unlike licenses), `occurred_at`. Indexes on `occurred_at`, `(actor_user_id, occurred_at)`, and `(target_type, target_id)`.
- **`cp:08-audit-log-immutability`** (`splitStatements:false`) — installs a PL/pgSQL trigger function `audit_log_block_modifications()` that `RAISE EXCEPTION`s on **any** `UPDATE` or `DELETE`, wired via `BEFORE UPDATE` and `BEFORE DELETE FOR EACH ROW` triggers. The audit log is **append-only at the database level** — even a SQL injection or a buggy service can't tamper with history.

> This blanket immutability is later *narrowly* relaxed in `18-webhook-fanout-integrity` to allow GDPR redaction of PII (see §7) — but `DELETE` stays categorically forbidden forever.

### `changes/09-sso.sql` — SSO providers

- **`sso_providers`** — per-org SAML/OIDC config. `org_id` FK (cascade), `type` enum (`SAML`/`OIDC`), `config_json JSONB`, `enabled`, `created_at`. Index on `org_id`.

> The skeleton stored everything (including secrets) in `config_json`. The `13-sso-hardening` changeset pulls secrets out into an encrypted column and adds identity binding + domain allow-listing (see §4).

### `changes/10-apikeys.sql` — programmatic credentials

- **`api_keys`** — org-scoped machine credentials. `key_hash` (the credential is stored **hashed**, never in clear), `key_prefix` (≤16 — the visible non-secret prefix shown in the UI and used to *locate* the row before a constant-time hash compare), `scopes_json`, `last_used_at`, `created_at`, `revoked_at`. Index on `org_id`, `key_prefix`, and a **unique** index on `key_hash`.

**Why both `key_prefix` and `key_hash`:** the prefix narrows the candidate row cheaply; the hash is the actual secret comparison. You never query by the full key.

### `changes/11-outbox.sql` — transactional outbox

- **`outbox_events`** — the classic transactional-outbox pattern so domain events are written *in the same transaction* as the state change, then published asynchronously. `aggregate_type`/`aggregate_id`, `event_type`, `payload_json`, `occurred_at`, `published_at` (nullable). A **partial** index `idx_outbox_events_unpublished ON (occurred_at) WHERE published_at IS NULL` keeps the "what's left to publish" scan tiny, plus an index on `(aggregate_type, aggregate_id)`.

> Massively expanded by `14-outbox-reliability` (retry/backoff/status/poison) and `18-webhook-fanout-integrity` (durable fan-out cursor) — see §5/§7.

### `changes/12-additional-permissions.sql` — permission catalog top-up

Pure data: inserts 14 more permission codes (`key.rotate`, `key.read`, `event.read`, `plan.read/create`, `subscription.create/suspend/cancel`, `license.read`, `org.create`, `org.members.add/remove`, `api-key.create/delete`) with `ON CONFLICT (code) DO NOTHING`, then grants **all** permissions to `SUPER_ADMIN` via `CROSS JOIN ... ON CONFLICT DO NOTHING`. This is the "we added endpoints, give the super admin the new authorities" top-up that recurs in later waves.

### `changes/99-auth-password-reset.sql` — password reset tokens

(Applied last per the master file.) **`password_reset_tokens`** — `user_id` FK (cascade), `token_hash` (the reset token is stored **hashed**), `expires_at`, `used_at` (single-use marker — non-null once consumed), `created_at`. Indexes on `user_id`, `token_hash` (lookup on redemption), and `expires_at` (cleanup sweep). The file's own header notes it was authored by the auth bucket and may need manual registration in the master YAML — which it now has.

---

## 4. P0 audit-fix wave (`13-*`)

These five changesets are the **P0 blockers** from the `2026-06-01` audit. Each maps directly to a finding family.

### `changes/13-session-revocation.sql` — bulk session revocation
> **Audit fix:** "no session/license revocation" P0.

```sql
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
```
`users.token_version` is the **durable source of truth** for the per-user token version. Every issued JWT carries a `tv` claim; `JwtAuthFilter` compares the claim against this column and rejects stale tokens. Incrementing this column **revokes all of a user's sessions at once** (logout-everywhere, force-reauth after password change). Redis (`cp:sess:tokenver:{userId}`) is a *write-through cache*; the column is authoritative precisely so a Redis flush can't silently *un*-revoke sessions.

### `changes/13-rbac-permissions.sql` — RBAC self-management perms
> **Audit fix:** RBAC privesc / missing role-management authorities.

Seeds `role.assign` and `rbac.read`, then grants: `role.assign`+`rbac.read` to `SUPER_ADMIN`, `role.assign` to `ORG_OWNER`, `rbac.read` to `ORG_OWNER`+`ORG_ADMIN`. All inserts use `ON CONFLICT DO NOTHING`. This makes "who can hand out roles" an explicit, gated permission rather than implicit — part of closing the privilege-escalation hole.

### `changes/13-usage-integrity.sql` — idempotent, sane metering
> **Audit fix:** usage tampering / double-count / negative-quantity integrity.

| Changeset | Change | Why |
|---|---|---|
| `cp:13-usage-events-event-id` | adds `usage_events.event_id VARCHAR(120)` | client-supplied idempotency key for ingest |
| `cp:13-usage-events-dedup-uidx` | **partial unique** `ux_usage_events_dedup (subscription_id, jti, event_id) WHERE event_id IS NOT NULL` | a retried ingest with the same `event_id` can't double-count; events *without* an id are exempt |
| `cp:13-usage-events-qty-check` | `CHECK (quantity >= 0)` | no negative usage to game quotas |
| `cp:13-usage-quotas-checks` | `consumed_value >= 0` and `limit_value IS NULL OR limit_value >= 0` | counters can't go negative; null limit = unlimited |
| `cp:13-usage-permissions-seed` | seeds `usage.read`, `usage.ingest`, `license.read` and grants them across the role tiers | RBAC for the metering endpoints |

### `changes/13-sso-hardening.sql` — SSO secrets, identity binding, domain pinning
> **Audit fix:** SSO secrets in plaintext config + missing identity binding (account-takeover risk).

- Seeds `sso.read` and grants `sso.read`/`sso.write` appropriately (incl. `sso.write` now to `ORG_ADMIN`).
- `cp:13-sso-providers-columns`: adds `sso_providers.client_secret_enc BYTEA` (the OIDC client secret, now **AES-GCM-encrypted**, out of `config_json`) and `allowed_email_domains TEXT` (CSV allow-list so an IdP can only assert identities in domains the org owns).
- `cp:13-sso-identities`: new **`sso_identities`** table — the durable bind from `(provider_id, subject)` → `user_id`. `CONSTRAINT ux_sso_identity UNIQUE (provider_id, subject)` means an IdP subject maps to exactly one local user, closing the "two IdP logins resolve to the same/wrong account" takeover path. FK cascade on both provider and user; index on `user_id`.

### `changes/13-audit-outcome.sql` — first-class DENIED/FAILED audit
> **Audit fix:** audit log only recorded successes; denied/failed security events were invisible.

```sql
ALTER TABLE audit_log ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE audit_log ADD CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','DENIED','FAILED'));
CREATE INDEX idx_audit_log_action_outcome ON audit_log (action, outcome, occurred_at);
```
Now an authorization denial or a failed login is a first-class audit row, indexed for "show me all DENIEDs for action X over time." The file's header also documents the intended **least-privilege production posture** (app role: `INSERT`/`SELECT`-only on `audit_log`, no `UPDATE`/`DELETE`/`TRUNCATE`/ownership; migrations under a separate privileged role) — *documented, not enforced here* to avoid breaking the single-role dev/test setup.

---

## 5. P1 depth wave (`14-*`, `15-*`)

### `changes/14-mfa.sql` — TOTP enrollment
> **P1 Wave 1.** **`user_mfa`** — PK *is* `user_id` (FK cascade), so one MFA enrollment per user. `secret_enc BYTEA` (the TOTP shared secret, **AES-GCM-encrypted** via `KeyEncryptor`, never clear). `enabled` flips to `true` only after the user confirms a code at `POST /api/v1/auth/mfa/verify`. `created_at`. Later gains `last_accepted_step` in `18-mfa-totp-replay` to stop code replay.

### `changes/14-outbox-reliability.sql` — multi-instance-safe publishing
> **Roadmap gaps #29 (multi-instance safety) & #68 (retry/backoff/DLQ).**

Adds to `outbox_events`: `attempts INT DEFAULT 0`, `next_attempt_at TIMESTAMPTZ`, `last_error TEXT`, `status VARCHAR(16) DEFAULT 'PENDING'` with `CHECK (status IN ('PENDING','PUBLISHED','FAILED'))`. Then:
- `cp:14-outbox-reliability-backfill-published`: `UPDATE ... SET status='PUBLISHED' WHERE published_at IS NOT NULL AND status='PENDING'` — legacy already-delivered rows must not be re-claimed once `status` drives the poller.
- `cp:14-outbox-reliability-claim-index`: partial index `idx_outbox_events_claimable (next_attempt_at, occurred_at) WHERE status='PENDING'`.

**The pattern:** `OutboxPublisher` claims a batch with `SELECT ... FOR UPDATE SKIP LOCKED` (no double-publish across instances), retries with exponential backoff via `next_attempt_at`, and quarantines poison messages to `FAILED` after max `attempts`.

### `changes/15-licensing.sql` — trials, seats, heartbeats
> **Wave 2 licensing depth.**

- `cp:15-license-tokens-type`: `license_tokens.license_type VARCHAR(20) DEFAULT 'STANDARD' CHECK (... IN ('STANDARD','TRIAL'))` — first-class trials (short TTL, distinct reporting/conversion).
- `cp:15-license-activations-create`: **`license_activations`** — one row per `(jti, node_id)`: a seat/node that activated against a license. `jti` FK to `license_tokens(jti)` (cascade). `node_id VARCHAR(190)` (the app's self-reported host/instance id). `first_seen_at`/`last_seen_at` (the heartbeat endpoint **upserts** `last_seen_at` each call). `last_seen_ip VARCHAR(45)` (text, same rationale as `license_tokens.last_seen_ip`). `CONSTRAINT ux_license_activations_jti_node UNIQUE (jti, node_id)` enables the upsert and prevents double-counting a node. Indexes on `jti` and `(jti, last_seen_at)` (count "active seats within the lease window").

> Note the FK references `license_tokens(jti)` — a **non-PK unique column** — which is legal because `jti` is `UNIQUE`.

### `changes/15-webhooks.sql` — signed outbound webhooks
> **Roadmap gap #33.** Two tables fed from the outbox.

- **`webhook_subscriptions`** — per-org registered HTTPS endpoint. `url TEXT`, `secret_enc BYTEA` (HMAC secret, **encrypted at rest**), `event_types TEXT` (CSV; **NULL = all events**), `active`. Index on `org_id` and a partial index on `active WHERE active=TRUE`.
- **`webhook_deliveries`** — one row per `(subscription, outbox event)` for at-least-once delivery. `status` (`PENDING`/`DELIVERED`/`FAILED`), `attempts`, `response_status`, `next_attempt_at`, `last_error`, `delivered_at`. **`CONSTRAINT ux_webhook_delivery UNIQUE (subscription_id, event_id)`** — the fan-out scanner relies on this (`ON CONFLICT DO NOTHING`) so the same event isn't enqueued to the same subscription twice across ticks/instances. Partial claim index `(next_attempt_at, created_at) WHERE status='PENDING'`.

> `event_id` here is a *plain* `UUID NOT NULL` (the `outbox_events.id`) with **no FK** — intentional, so a webhook delivery row survives outbox pruning.

### `changes/15-compliance.sql` — GDPR erasure ledger
> **Wave 2 GDPR.** **`erasure_log`** — one row per data-subject request. `subject_type` (`user`/`org`), `subject_id`, `requested_by` (the initiating actor; nullable), `requested_at`, `completed_at` (NULL = started-but-failed, distinguishing it from a finished request), `action` (`export`/`erase`). Indexes on `(subject_type, subject_id)` and `requested_at`.

**Key design note (from the header):** this migration adds *no* new PII columns — erasure **pseudonymises in place** via `ErasureService` using existing repositories. This table is the audit-of-record proving a request happened and completed.

### `changes/15-idempotency.sql` — server-side Idempotency-Key
> **Roadmap gap #81.** **`idempotency_keys`** — keys a mutating request by `(idem_key, method, path, actor_user_id)` (the `ux_idempotency_key` UNIQUE constraint). `request_hash` (SHA-256 hex of the body — lets a same-key retry with a *different* payload be flagged). `response_status`/`response_body` (NULL while first request is in flight; populated on completion so retries **replay** the stored outcome instead of re-executing the side effect). Index on `created_at` for the TTL sweep.

**Actor-scoping nuance:** `actor_user_id` is the human user id, *or* the bound org id for API-key principals, *or* the literal `'anonymous'` — so the same key replayed by a *different* caller can't read back someone else's prior response. Extended by `18-idempotency-replay-headers` to also restore `Content-Type`/`Location`.

---

## 6. P2 enterprise wave (`16-*`)

### `changes/16-scim.sql` — SCIM 2.0 provisioning bridge
**`scim_user_mappings`** — per-org link from an IdP `external_id` to a control-panel `user_id`. `org_id` FK (cascade), `external_id TEXT` (nullable), `user_id` FK (cascade), `created_at`. **`CONSTRAINT ux_scim_user_mappings_org_external UNIQUE (org_id, external_id)`** — a given IdP externalId maps to at most one user per tenant (the externalId is the SCIM client's stable namespace). Index `(org_id, user_id)` for "the mapping for this user in my org." Seeds the `scim.manage` permission (carried by the org-bound API-key SCIM credential; also granted to `SUPER_ADMIN`).

> The **user-side** uniqueness (`org_id, user_id`) is added later by `18-webhook-fanout-integrity` (see §7), closing a check-then-insert race that could create duplicate mappings.

### `changes/16-billing.sql` — provider-agnostic invoicing
> **Roadmap gaps #74 (invoicing) & #75 (usage rating)**; the payment provider itself (#73) is deliberately out of scope (`ManualBillingProvider` records to the DB with no external call).

- **`billing_accounts`** — one per org (`org_id UNIQUE`, cascade). `provider VARCHAR(64)`, `external_customer_id TEXT`, `currency VARCHAR(3) DEFAULT 'USD'`.
- **`invoices`** — a rated period for a subscription. `org_id`+`subscription_id` FKs (cascade), `period_start`/`period_end`, `status` (`DRAFT`/`ISSUED`/`PAID`/`VOID`), `currency`, `total_amount NUMERIC DEFAULT 0 CHECK (>= 0)`, `issued_at`, `created_at`. Indexes on `org`, `subscription`, `status`.
- **`invoice_line_items`** — `invoice_id` FK (cascade), `feature_key`, `quantity`, `unit_amount`, `amount`, `description`. Index on `invoice_id`. (These sum to the invoice total.)
- Seeds `billing.read` and grants it to **all five** role tiers.

> Hardened by `18-billing-invoice-integrity`: `@Version` column + a partial-unique "one non-VOID invoice per (subscription, period)" guard (see §7).

### `changes/16-keys.sql` — COMPROMISED key state
> **KEK rotation + compromised-key handling.**

```sql
ALTER TABLE signing_keys DROP CONSTRAINT IF EXISTS signing_keys_status_check;
ALTER TABLE signing_keys ADD CONSTRAINT signing_keys_status_check
    CHECK (status IN ('ACTIVE','RETIRED','COMPROMISED'));
```
Widens the status enum to add a third terminal state, `COMPROMISED`, so a leaked signing key can be flagged and **immediately excluded from the published JWKS** — offline verifiers stop trusting it at their next JWKS refresh. Because only the *allowed value set* grows (column type unchanged), `ddl-auto=validate` still passes.

**KEK rotation note (from the header):** re-encrypting `private_key_encrypted` under a new active KEK is a **pure data operation** in `KeyService.rotateKek`, *not* a schema change — so there's no DDL for it. The ciphertext is self-describing (a versioned blob carries its KEK id inline; legacy unversioned blobs decrypt under the default KEK).

---

## 7. Optimistic-locking & integrity finishers (`17-*`, `18-*`)

This last wave is almost entirely about **concurrency correctness** (lost-update prevention via JPA `@Version`) and **invariant enforcement at the DB level** (unique constraints/indexes the application logic alone couldn't guarantee under READ COMMITTED).

### `changes/17-optimistic-locking.sql` — @Version on hot aggregates
Adds `version BIGINT NOT NULL DEFAULT 0` to **`users`**, **`organizations`**, and **`subscriptions`**. Hibernate increments it on each `UPDATE`; a concurrent second writer fails with `OptimisticLockException` instead of silently clobbering the first (multi-instance lost-update prevention). Existing rows start at 0.

### `changes/18-apikey-optimistic-lock.sql`
`api_keys.version BIGINT NOT NULL DEFAULT 0`. **Why:** a concurrent `verify()` writing `last_used_at` must not silently overwrite a committed `revoke()` — without `@Version` the last-used flush could resurrect a revoked key.

### `changes/18-license-token-optimistic-lock.sql` — three fixes
The richest `18` file:
- `cp:18-license-tokens-version` (**P1-8**): `version` column so a heartbeat's `last_seen` flush can't rewrite a committed revocation/expiry **back to ACTIVE**.
- `cp:18-license-tokens-expiring-warned-at` (**P2**): `expiring_warned_at TIMESTAMPTZ` — a durable "warned about expiry" marker so the lifecycle sweeper warns **at most once**, replacing the racy "count the outbox rows" heuristic (which re-warned once the outbox was purged). NULL = never warned.
- `cp:18-license-artifacts-create` (**P1-4**): new **`license_artifacts`** table — persists the *exact* signed `.lic` at issue time. PK `jti` (FK to `license_tokens(jti)`, cascade). `jwt TEXT` (the raw signed JWT), `kid`, `plan_code`, `org_name`, `org_slug`, `issued_at`, `expires_at`, `created_at`. **Security rationale:** previously a download cache-miss *re-minted a brand-new license* (new `jti`/row/outbox event, zero audit) reachable by a **read-only** principal. Now `GET /licenses/{jti}/download` is a pure read of an immutable artifact.
- `cp:18-license-tokens-subscription-fk-restrict` (**P3**): re-points `license_tokens.subscription_id` FK from `ON DELETE CASCADE` to `ON DELETE RESTRICT`, so a (hypothetical) hard delete of a subscription can't silently erase the revocation history (the `jti`s that must stay on the CRL). Nothing in the codebase hard-deletes subscriptions (lifecycle uses status transitions), so this only blocks a should-never-happen destructive op.

### `changes/18-billing-invoice-integrity.sql`
- `cp:18-invoices-version`: `@Version` so a concurrent issue/void can't silently overwrite each other (e.g., a `VOID` rewritten back to `ISSUED`).
- `cp:18-invoices-unique-subscription-period`: **partial unique** index `uq_invoices_subscription_period (subscription_id, period_start) WHERE status <> 'VOID'` — only one non-VOID invoice per `(subscription, period)`; a VOIDed invoice can be superseded by a fresh draft. The service also does an in-app check and catches this violation to return idempotently under a generate race.

### `changes/18-webhook-fanout-integrity.sql` — outbox cursor + SCIM uniqueness + GDPR redaction
Despite the filename, this single file (owned by the "webhooks/integrations fixer") carries **three** unrelated-but-co-located fixes:
- `cp:18-webhook-outbox-fanned-out-marker` (**P1-11**): adds `outbox_events.fanned_out_at TIMESTAMPTZ` so webhook fan-out becomes a **durable, replay-safe, at-least-once cursor** (claim `WHERE fanned_out_at IS NULL` via `FOR UPDATE SKIP LOCKED`, enqueue deliveries, then stamp `fanned_out_at`) instead of a best-effort time-window lookback that could drop aged-out events. Adds partial index `idx_outbox_events_unfanned (occurred_at) WHERE fanned_out_at IS NULL` plus a plain `idx_outbox_events_occurred_at` for range scans.
- `cp:18-scim-user-mappings-unique-org-user` (**P2**): `ux_scim_user_mappings_org_user UNIQUE (org_id, user_id)` — the *user-side* uniqueness that complements `16-scim`'s `(org_id, external_id)`; closes a check-then-insert / re-provision race that could duplicate mappings (service catches it → SCIM 409).
- `cp:18-audit-log-allow-gdpr-redaction` (`splitStatements:false`, **P2**): **redefines** the `audit_log_block_modifications()` trigger function. It now permits an `UPDATE` **only** when the transaction has set the session GUC `app.audit_redaction = 'on'` (set `LOCAL` by `ErasureService` inside the erasure transaction) **and** the update touches *only* `payload_json`/`ip_address` — never the identity/integrity columns (`id`, `actor_user_id`, `actor_org_id`, `action`, `target_type`, `target_id`, `occurred_at`, `outcome`). `DELETE` remains categorically forbidden. This threads the needle between GDPR Art. 17 erasure (must scrub embedded PII) and the tamper-evidence guarantee of the security trail.

### `changes/18-keys-active-signing-index.sql`
`ux_signing_keys_single_active UNIQUE (status) WHERE status='ACTIVE'` — **at most one ACTIVE signing key**. `KeyService.generateNewActiveKey` retires existing ACTIVE keys before inserting the new one, but under READ COMMITTED two concurrent rotate/bootstrap transactions could each pass the "retire" step and both insert ACTIVE rows. The partial unique index makes the second committer fail. RETIRED/COMPROMISED keys (potentially many) are unaffected. `IF NOT EXISTS` for re-run safety.

### `changes/18-subscription-overrides-unique.sql` — deterministic entitlements
- `cp:18-suboverride-dedupe`: defensively collapses pre-existing duplicate `(subscription_id, type, key)` rows (keep min `id`, delete the rest) before the constraint can be added. No-op on a fresh DB; rollback is `SELECT 1;` (irreversible).
- `cp:18-suboverride-unique` (**P3**): `ux_subscription_overrides_sub_type_key UNIQUE (subscription_id, type, key)`. Without it, two overrides for the same `(subscription, type, key)` could coexist and `resolveEntitlements()` became **order-dependent** (whichever `findBySubscriptionId` returned last won). Now exactly one override per key → deterministic resolution.

### `changes/18-mfa-totp-replay.sql`
`user_mfa.last_accepted_step BIGINT` (nullable) — the 30-second TOTP time-step (`epoch_seconds / 30`) of the most-recently-accepted code. `MfaService` rejects any code whose step is `<= last_accepted_step`, closing the ~90s replay window left open by `allowedTimePeriodDiscrepancy=1`. NULL = fresh enrollment, nothing accepted yet.

### `changes/18-idempotency-replay-headers.sql`
Adds `idempotency_keys.response_content_type VARCHAR(128)` and `response_location VARCHAR(2048)` so a replayed idempotent response restores its `Content-Type` and `Location` headers (previously only status+body were replayed). Required for context start under `ddl-auto=validate` because the `IdempotencyKey` entity already references them.

---

## 8. Consolidated data model

### 8.1 Table catalog

| Table | Purpose | PK | Notable FKs (on delete) | Key constraints/indexes |
|---|---|---|---|---|
| `organizations` | Tenant | `id` | — | `slug` UNIQUE; `version`(17) |
| `users` | Global human identity | `id` | — | `email` CITEXT UNIQUE; `super_admin`; `token_version`(13); `version`(17) |
| `org_members` | User↔org membership + role | `(org_id,user_id)` | org→orgs (CASCADE), user→users (CASCADE) | role CHECK |
| `roles` | RBAC role catalog | `id` | — | `code` UNIQUE |
| `permissions` | RBAC permission catalog | `id` | — | `code` UNIQUE |
| `role_permissions` | role→perm grants | `(role_id,permission_id)` | both CASCADE | — |
| `user_roles` | user→role (global/org-scoped) | `(user_id,role_id,org_id_key)` | all CASCADE | `org_id_key` generated COALESCE sentinel |
| `plans` | Product catalog | `id` | — | `code` UNIQUE; `default_ttl_days` |
| `plan_permissions` | plan feature-perms | `(plan_id,permission_code)` | plan→plans (CASCADE) | `permission_code` is free-text |
| `plan_features` | plan feature flags | `(plan_id,feature_key)` | plan→plans (CASCADE) | `value_json` JSONB |
| `subscriptions` | Org's purchase window | `id` | org (CASCADE), plan (—), created_by (—) | status CHECK; `ends_at` idx; `version`(17) |
| `subscription_overrides` | Per-sub entitlement deltas | `id` | sub (CASCADE) | `ux ...(sub,type,key)`(18) |
| `signing_keys` | Ed25519 keypairs | `id` | — | `kid` UNIQUE; status CHECK (+COMPROMISED,16); single-ACTIVE partial-unique(18) |
| `license_tokens` | Issued licenses / CRL source | `id` | sub (**RESTRICT** after 18) | `jti` UNIQUE; status/type CHECK; `version`(18); `expiring_warned_at`(18) |
| `license_artifacts` | Immutable signed `.lic` (18) | `jti` | jti→license_tokens (CASCADE) | append-only, no update path |
| `license_activations` | Seat/node heartbeats (15) | `id` | jti→license_tokens (CASCADE) | `ux (jti,node_id)` |
| `usage_events` | Append-only metering | `id` | sub (CASCADE) | `event_id`(13); dedup partial-unique(13); qty≥0(13) |
| `usage_quotas` | Period counters | `(sub,feature_key,period_start)` | sub (CASCADE) | consumed/limit ≥0(13) |
| `audit_log` | Tamper-evident trail | `id` | — (loose actor ids) | append-only triggers (08/18); `outcome`(13) |
| `sso_providers` | Per-org SAML/OIDC | `id` | org (CASCADE) | `client_secret_enc`(13); `allowed_email_domains`(13) |
| `sso_identities` | IdP subject→user bind (13) | `id` | provider (CASCADE), user (CASCADE) | `ux (provider_id,subject)` |
| `api_keys` | Machine credentials | `id` | org (CASCADE) | `key_hash` UNIQUE; `version`(18) |
| `outbox_events` | Transactional outbox | `id` | — | partial unpublished/claimable/unfanned idxs; status CHECK(14); `fanned_out_at`(18) |
| `webhook_subscriptions` | Per-org webhook endpoint (15) | `id` | org (CASCADE) | `secret_enc` encrypted |
| `webhook_deliveries` | Per-event delivery (15) | `id` | sub→webhook_subscriptions (CASCADE) | `ux (subscription_id,event_id)`; `event_id` no FK |
| `scim_user_mappings` | IdP externalId→user (16) | `id` | org (CASCADE), user (CASCADE) | `ux (org,external_id)`; `ux (org,user_id)`(18) |
| `billing_accounts` | Org billing binding (16) | `id` | org (CASCADE, UNIQUE) | one per org |
| `invoices` | Rated period (16) | `id` | org (CASCADE), sub (CASCADE) | status CHECK; total≥0; `version`(18); non-VOID partial-unique per period(18) |
| `invoice_line_items` | Rated lines (16) | `id` | invoice (CASCADE) | — |
| `idempotency_keys` | Replay store (15) | `id` | — | `ux (key,method,path,actor)`; header cols(18) |
| `erasure_log` | GDPR request ledger (15) | `id` | — (loose subject/actor ids) | subject_type/action CHECK |
| `user_mfa` | TOTP enrollment (14) | `user_id` | user→users (CASCADE) | `secret_enc` encrypted; `last_accepted_step`(18) |
| `password_reset_tokens` | Reset tokens (99) | `id` | user (CASCADE) | `token_hash`; single-use `used_at` |

*(Parenthesized numbers indicate the migration wave that added the column/constraint.)*

### 8.2 ASCII ER overview

```
                          ┌──────────────────┐
                          │   organizations  │ (tenant root)
                          └──────────────────┘
            ┌──────────────┬───────┴───────────┬───────────────┬───────────────┐
            │              │                   │               │               │
   ┌────────▼───────┐ ┌────▼─────────┐  ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼────────┐
   │  org_members   │ │subscriptions │  │sso_providers│ │  api_keys   │ │billing_account│
   │ (org,user) PK  │ │              │  │             │ │             │ │ org UNIQUE    │
   └────────┬───────┘ └──┬────────┬──┘  └──────┬──────┘ └─────────────┘ └───────────────┘
            │            │        │            │
   ┌────────▼───────┐    │        │     ┌──────▼────────┐
   │     users      │◄───┘ created_by   │ sso_identities│──► users
   │ (global ident) │    │        │     │ ux(prov,subj) │
   └───┬───────┬────┘    │        │     └───────────────┘
       │       │         │        │
       │  ┌────▼──────┐  │   ┌────▼────────────────┐    ┌─────────────────────┐
       │  │ user_mfa  │  │   │ subscription_       │    │  plans  (catalog)   │
       │  │ (TOTP)    │  │   │ overrides           │    │   ▲          ▲      │
       │  └───────────┘  │   │ ux(sub,type,key)    │    │   │          │      │
       │                 │   └─────────────────────┘    │ plan_     plan_     │
   ┌───▼──────────┐      │                              │ permissions features│
   │ user_roles   │──► roles ──► role_permissions ──► permissions             │
   │ (global/org) │      ▲                                                    │
   └──────────────┘      └──────────── subscriptions.plan_id ─────────────────┘
                                       │
              ┌────────────────────────┼──────────────────────────┐
              │                        │                          │
     ┌────────▼─────────┐    ┌─────────▼────────┐        ┌────────▼─────────┐
     │  license_tokens  │    │   usage_events   │        │     invoices     │
     │  jti UNIQUE (CRL)│    │   usage_quotas   │        │  + line_items    │
     │  FK sub RESTRICT │    └──────────────────┘        │  non-VOID UNIQUE │
     └───┬───────────┬──┘                                │  per period      │
         │ jti       │ jti                               └──────────────────┘
   ┌─────▼──────┐ ┌──▼───────────────┐
   │license_    │ │license_          │      signing_keys ──(kid)──► license_tokens.kid
   │artifacts   │ │activations       │      (≤1 ACTIVE; +COMPROMISED)  / published JWKS
   │ jti PK     │ │ ux(jti,node_id)  │
   └────────────┘ └──────────────────┘

   Cross-cutting / loosely-coupled (no hard FK to keep them durable):
     audit_log (append-only)   outbox_events ──(id)──► webhook_deliveries ──► webhook_subscriptions
     erasure_log               idempotency_keys        scim_user_mappings (org+user)
     password_reset_tokens
```

### 8.3 Relationship & isolation notes

- **Tenant isolation (the IDOR family from the audit):** almost every business table carries an `org_id` (directly or transitively via `subscription_id`). Services *must* filter by the caller's org. The schema reinforces this with `ON DELETE CASCADE` from `organizations` so deleting a tenant removes its data graph — except where history must survive (`license_tokens.subscription_id` is `RESTRICT`; `webhook_deliveries.event_id` and the `audit_log`/`erasure_log` actor ids are deliberately **un-FK'd** so pruning/erasure don't cascade away the record-of-what-happened).
- **The CRL chain:** `signing_keys.kid` → `license_tokens.kid` (signed-with), and `license_tokens.jti` is the revocation key referenced by `license_artifacts.jti` and `license_activations.jti`. Revocation lives in `license_tokens` (status/`revoked_at`); offline verifiers learn it via the published CRL.
- **Encryption-at-rest columns** (`BYTEA`, AES-GCM via `KeyEncryptor`): `signing_keys.private_key_encrypted`, `sso_providers.client_secret_enc`, `user_mfa.secret_enc`, `webhook_subscriptions.secret_enc`. **Hashed-not-stored** secrets: `users.password_hash`, `api_keys.key_hash`, `password_reset_tokens.token_hash`.
- **Append-only tables:** `audit_log` (DB triggers — UPDATE allowed only for flagged GDPR redaction; DELETE never), `usage_events` (logically append-only), `license_artifacts` (no update path by design).

---

## 9. Gotchas a new engineer must know

1. **Apply order = master YAML, not filenames.** Many `13-*`/`18-*` files share a prefix; the `db.changelog-master.yaml` `include:` order is authoritative. Never reorder includes lightly.
2. **Never edit an applied changeset.** Liquibase checksums each one; editing breaks the deploy. Always add a new changeset.
3. **`ddl-auto=validate` is unforgiving.** Add an entity field → you *must* add a matching column changeset, or the Spring context won't start. The header comments in `14-`/`15-`/`16-` files document the exact column↔field mappings for this reason.
4. **`last_seen_ip` is `VARCHAR(45)`, not `inet`** (in `license_tokens` and `license_activations`) — deliberate, to match the `String`-mapped JPA field. `audit_log.ip_address`, by contrast, *is* `inet`.
5. **Enum widening is a constraint dance.** To add an enum value you `DROP CONSTRAINT IF EXISTS ...` then `ADD CONSTRAINT ... CHECK (... IN (...))` (see `16-keys`). The PG-generated constraint name is `<table>_<col>_check`.
6. **Partial unique indexes enforce "at most one X" invariants** the app can't guarantee under READ COMMITTED: one ACTIVE signing key (`18-keys`), one non-VOID invoice per period (`18-billing`), one usage event per `event_id` (`13-usage`). Expect the *second* concurrent writer to fail with a unique violation — the services **catch** these and return idempotently/409, so don't "fix" that by removing the index.
7. **`user_roles` global grants rely on the generated `org_id_key`** sentinel — don't query/dedupe on raw `org_id` for global roles; use the COALESCE'd key.
8. **`audit_log` mutation is trigger-enforced.** Any `UPDATE` outside a `SET LOCAL app.audit_redaction='on'` transaction (and any `DELETE`, ever) raises an exception. If a test/service "can't update audit_log," that's working as intended.
9. **KEK rotation has no DDL.** Re-encrypting `signing_keys.private_key_encrypted` under a new KEK is pure data (`KeyService.rotateKek`); the ciphertext is self-describing. Don't look for a migration.
10. **`plan_permissions.permission_code` is *not* an FK** to `permissions.code` — those are product/feature strings baked into the license JWT, a different namespace from RBAC permissions.
11. **Deliberately un-FK'd columns** (`webhook_deliveries.event_id`, audit/erasure actor/subject ids) are intentional so durable records survive the pruning/erasure of what they reference. Don't "tidy" them into FKs.
12. **Least-privilege DB roles are documented but not enforced** in the changelog (to keep dev/test single-role). Production should split a privileged migration role from a restricted app role (no UPDATE/DELETE on `audit_log`, etc.) — see the `13-audit-outcome` header.
