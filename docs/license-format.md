# License File Format Specification

**Status:** v1 (stable contract)
**Audience:** SDK implementers, integrators reverse-engineering the artifact, security reviewers.

This document is the canonical description of the `.lic` artifact produced by the control panel and consumed by `license-verifier` (and the Spring Boot starter that wraps it). It is the public contract between the control plane and every customer's Docker app. **Treat it as semver:** any change that breaks an already-deployed verifier requires a bumped `version` claim.

---

## 1. Format at a glance

A license is a **signed JWT**, wrapped in a small **JSON envelope** that exists purely so a human opening the file in a text editor can tell what it is.

```
license.lic  ->  { "license": "<JWT>", ...metadata... }
                       │
                       ▼
                base64url(header).base64url(claims).base64url(signature)
```

The verifier reads `license`, validates the JWT, and ignores the rest of the envelope.

---

## 2. Cryptography

### 2.1 Algorithm

| Field | Value |
|---|---|
| `alg` | `EdDSA` (Ed25519 curve) |
| Signature size | 64 bytes (~86 chars base64url) |
| Public key size | 32 bytes |
| Library | `nimbus-jose-jwt` 9.x on the JVM |

**Why EdDSA over RS256?** Signatures are ~4x smaller, verification is ~10x faster, and Ed25519 is the modern default with no parameter-selection footguns. JDK 17+ supports it natively (`KeyPairGenerator.getInstance("Ed25519")`), so no BouncyCastle dependency is required on the consumer side.

### 2.2 Key identification

Each signing key has a unique `kid` (key ID). The JWT header carries the `kid`; the verifier looks the key up in the JWKS by that ID. This is how rotation works without forcing customer redeploys.

`kid` format: `key-YYYY-MM-DD[-suffix]`, e.g. `key-2026-06-01` or `key-2026-06-01-emergency`.

---

## 3. JWT header

```json
{
  "alg": "EdDSA",
  "kid": "key-2026-06-01",
  "typ": "license+jwt"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `alg` | string | yes | Always `EdDSA` in v1. Verifier MUST reject any other value (no algorithm-agility bugs). |
| `kid` | string | yes | References an entry in the control plane's JWKS. |
| `typ` | string | yes | Fixed to `license+jwt` so generic JWT tooling can distinguish licenses from session tokens. |

---

## 4. JWT claims

```json
{
  "iss": "https://control-panel.example.com",
  "aud": ["docker-app-prod"],
  "sub": "org_01HXYZ4QH2VABCDEF",
  "jti": "lic_01HXYZ4QH2VABCDEF",
  "iat": 1717200000,
  "nbf": 1717200000,
  "exp": 1748736000,
  "subscription_id": "sub_01HXYZ4QH2VABCDEF",
  "plan": "pro",
  "permissions": ["export.pdf", "api.v2", "admin.users.invite"],
  "features": {
    "max_users": 50,
    "max_storage_gb": 100,
    "ai_assistant": true
  },
  "seats": 25,
  "customer": {
    "org_name": "Example Corp",
    "contact_email": "billing@example.com"
  },
  "version": 1
}
```

### 4.1 Standard JWT claims

| Claim | Type | Required | Semantics |
|---|---|---|---|
| `iss` | string (URL) | yes | Issuing control panel. Verifier MAY require an exact match if configured. |
| `aud` | string[] | yes | Always an array (even when there's one entry). Verifier MUST match at least one of its configured audiences. |
| `sub` | string | yes | Organization ID the license belongs to. Opaque to the verifier. |
| `jti` | string | yes | Unique token ID. Used for revocation lookups and usage event correlation. |
| `iat` | number (epoch seconds) | yes | Time of issue. |
| `nbf` | number | yes | Not-before. Verifier MUST reject if `now < nbf - clockSkew`. |
| `exp` | number | yes | Expiry. Verifier MUST reject if `now > exp + clockSkew`. |

### 4.2 License-specific claims

| Claim | Type | Required | Semantics |
|---|---|---|---|
| `subscription_id` | string | yes | Links the token back to the subscription record. Useful for correlation in usage events. |
| `plan` | string | yes | Plan code (e.g. `starter`, `pro`, `enterprise`). Informational; do not gate logic on it — gate on `permissions`. |
| `permissions` | string[] | yes | The actual entitlement set. Empty array allowed (a license with no permissions still validates and grants nothing). |
| `features` | object | yes | Mixed-type feature flags and quotas. Keys are stable identifiers (e.g. `max_users`); values may be boolean, integer, or string. |
| `seats` | integer | yes | Licensed user count. `0` means "uncapped". Enforcement is the application's responsibility. |
| `customer` | object | yes | Human-readable identification. `{ org_name: string, contact_email: string }`. Surfaced in `/actuator/license` for ops. |
| `version` | integer | yes | Schema version. Currently `1`. Verifiers MUST reject unknown versions. |

### 4.3 Permission codes

Permissions are dotted, lowercase strings: `<domain>.<action>` or `<domain>.<resource>.<action>`. Examples used elsewhere in the platform:

- `export.pdf`
- `export.csv`
- `api.v2`
- `admin.users.invite`
- `analytics.read`
- `analytics.export`

They are **license entitlements**, not control-panel RBAC permissions. Do not collapse the two systems.

### 4.4 Feature values

`features` is a flat object with three permitted value types:

- **boolean** — feature flag (`"ai_assistant": true`)
- **integer** — quota / cap (`"max_users": 50`)
- **string** — tier label (`"support_tier": "premium"`)

Nested objects and arrays are forbidden in v1 to keep verifier code simple. If you need structured features, version-bump.

---

## 5. The `.lic` envelope

The file customers download. UTF-8 JSON, no BOM.

```json
{
  "license": "eyJhbGciOiJFZERTQSIsImtpZCI6ImtleS0yMDI2LTA2LTAxIiwidHlwIjoibGljZW5zZStqd3QifQ.eyJpc3MiOiJodHRwczovL2NvbnRyb2wtcGFuZWwuZXhhbXBsZS5jb20iLCJhdWQiOlsiZG9ja2VyLWFwcC1wcm9kIl0sInN1YiI6Im9yZ18wMUhYWVo0UUgyVkFCQ0RFRiIsImp0aSI6ImxpY18wMUhYWVo0UUgyVkFCQ0RFRiIsImlhdCI6MTcxNzIwMDAwMCwibmJmIjoxNzE3MjAwMDAwLCJleHAiOjE3NDg3MzYwMDAsInN1YnNjcmlwdGlvbl9pZCI6InN1Yl8wMUhYWVo0UUgyVkFCQ0RFRiIsInBsYW4iOiJwcm8iLCJwZXJtaXNzaW9ucyI6WyJleHBvcnQucGRmIiwiYXBpLnYyIiwiYWRtaW4udXNlcnMuaW52aXRlIl0sImZlYXR1cmVzIjp7Im1heF91c2VycyI6NTAsIm1heF9zdG9yYWdlX2diIjoxMDAsImFpX2Fzc2lzdGFudCI6dHJ1ZX0sInNlYXRzIjoyNSwiY3VzdG9tZXIiOnsib3JnX25hbWUiOiJBY21lIENvcnAiLCJjb250YWN0X2VtYWlsIjoiYmlsbGluZ0BhY21lLmNvbSJ9LCJ2ZXJzaW9uIjoxfQ.(signature redacted)",
  "issued_at": "2026-06-01T10:00:00Z",
  "customer": "Example Corp",
  "plan": "Pro",
  "expires_at": "2027-06-01T10:00:00Z",
  "notes": "Generated by control-panel.example.com. Drop this file at /etc/app/license.lic."
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `license` | string | yes | The signed JWT. The verifier reads only this. |
| `issued_at` | string (ISO 8601 UTC) | recommended | Human-readable copy of `iat`. |
| `customer` | string | recommended | Mirror of `customer.org_name` for at-a-glance identification. |
| `plan` | string | recommended | Mirror of `plan`, title-cased for humans. |
| `expires_at` | string (ISO 8601 UTC) | recommended | Mirror of `exp`. |
| `notes` | string | optional | Free-form support note. |

The envelope is **decorative metadata**. The JWT inside is the source of truth. Tampering with the envelope changes nothing; tampering with the JWT invalidates the signature.

---

## 6. Verification rules

A verifier implementation MUST, in order:

1. **Parse the envelope.** Extract `license`. If missing or not a string -> reject as malformed.
2. **Decode the JWT header.** Reject if `alg != "EdDSA"`, `typ != "license+jwt"`, or `kid` is missing.
3. **Resolve the key.** Look up `kid` in the configured JWKS. If unknown -> reject (do *not* fall back to algorithm-agnostic verification).
4. **Verify the signature** using Ed25519. If invalid -> reject.
5. **Validate temporal claims** with the configured clock skew (default 5 minutes):
   - Reject if `now < nbf - skew`.
   - Reject if `now > exp + skew` (but see "expired mode" below).
6. **Validate audience.** Reject if the configured audience is not in the `aud` array.
7. **Validate issuer** if configured. Reject if `iss` does not exactly match.
8. **Validate `version`.** Reject if `version != 1` (forward-compat: verifiers refuse unknown future versions rather than silently misinterpreting).
9. **Optionally consult CRL.** If the verifier is online-enabled and a recent CRL is cached, reject if `jti` is listed as revoked.
10. **Expose snapshot.** Cache `permissions`, `features`, `seats`, `plan`, `expires_at`, `status` for the runtime to query.

### 6.1 Failure behaviour

| Condition | Default behaviour | Configurable? |
|---|---|---|
| File missing | Refuse to start (strict) or boot with no permissions (lenient) | Yes (`app.license.strict`) |
| Signature invalid | Refuse to start | No |
| Expired | Start in read-only mode, expose status `EXPIRED` | Behaviour tunable, rejection not |
| Wrong audience | Refuse to start | No |
| JWKS unreachable (refresh URL) | Use cached keys, retry with backoff | Yes (`app.license.refresh-interval`) |

### 6.2 Clock skew

Default tolerance is **5 minutes** in both directions. Customers running in environments with bad time sync (e.g. some on-prem boxes) can raise this; the verifier MUST NOT accept arbitrary skew silently.

---

## 7. Key rotation & JWKS

The control panel publishes its JWKS at:

```
GET https://control-panel.example.com/.well-known/jwks.json
```

```json
{
  "keys": [
    {
      "kty": "OKP",
      "crv": "Ed25519",
      "kid": "key-2026-06-01",
      "use": "sig",
      "alg": "EdDSA",
      "x": "base64url(public key bytes)"
    },
    {
      "kty": "OKP",
      "crv": "Ed25519",
      "kid": "key-2025-12-01",
      "use": "sig",
      "alg": "EdDSA",
      "x": "base64url(public key bytes)"
    }
  ]
}
```

**Rotation policy:**

1. A new active key is generated and published. New licenses are signed with it.
2. The previous key remains in the JWKS for at least the longest outstanding token TTL (default: 18 months) so old licenses keep verifying.
3. After that grace window the key is removed from the JWKS (retired). Any license still signed with it then fails verification.
4. **Emergency rotation** (key compromise): old key is removed immediately; affected licenses are reissued with the new key; CRL entries cover the gap.

Customers consuming the static JWKS (bundled in their image) must re-pull the JWKS file before the rotation grace window expires. Customers using `app.license.refresh-from-url` get rotations for free.

---

## 8. Reference examples

### 8.1 Decoded JWT (signature redacted)

Header:
```json
{ "alg": "EdDSA", "kid": "key-2026-06-01", "typ": "license+jwt" }
```

Payload:
```json
{
  "iss": "https://control-panel.example.com",
  "aud": ["docker-app-prod"],
  "sub": "org_01HXYZ4QH2VABCDEF",
  "jti": "lic_01HXYZ4QH2VABCDEF",
  "iat": 1717200000,
  "nbf": 1717200000,
  "exp": 1748736000,
  "subscription_id": "sub_01HXYZ4QH2VABCDEF",
  "plan": "pro",
  "permissions": ["export.pdf", "api.v2", "admin.users.invite"],
  "features": { "max_users": 50, "max_storage_gb": 100, "ai_assistant": true },
  "seats": 25,
  "customer": { "org_name": "Example Corp", "contact_email": "billing@example.com" },
  "version": 1
}
```

Compact serialization (signature redacted):
```
eyJhbGciOiJFZERTQSIsImtpZCI6ImtleS0yMDI2LTA2LTAxIiwidHlwIjoibGljZW5zZStqd3QifQ.eyJpc3MiOiJodHRwczovL2NvbnRyb2wtcGFuZWwuZXhhbXBsZS5jb20iLCJhdWQiOlsiZG9ja2VyLWFwcC1wcm9kIl0sLi4ufQ.(signature redacted)
```

### 8.2 Minimal license (free / trial)

A trial license has no permissions and minimal features. It still validates and provides observable status; it just gates nothing.

```json
{
  "iss": "https://control-panel.example.com",
  "aud": ["docker-app-prod"],
  "sub": "org_01HXYZTRIAL",
  "jti": "lic_01HXYZTRIAL",
  "iat": 1717200000,
  "nbf": 1717200000,
  "exp": 1719792000,
  "subscription_id": "sub_01HXYZTRIAL",
  "plan": "trial",
  "permissions": [],
  "features": { "max_users": 5 },
  "seats": 5,
  "customer": { "org_name": "Test Co", "contact_email": "qa@test.example" },
  "version": 1
}
```

### 8.3 `.lic` envelope on disk

```json
{
  "license": "eyJhbGciOiJFZERTQSIs...(signature redacted)",
  "issued_at": "2026-06-01T10:00:00Z",
  "customer": "Example Corp",
  "plan": "Pro",
  "expires_at": "2027-06-01T10:00:00Z",
  "notes": "Generated by control-panel.example.com. Drop this file at /etc/app/license.lic."
}
```

---

## 9. Compatibility & versioning

- The `version` claim is the schema version of the *claims object*, not the verifier protocol.
- Adding a new optional claim is a **non-breaking** change (verifiers must ignore unknown claims).
- Removing a claim, changing a claim's type, or changing the meaning of `permissions[]` is **breaking** -> requires `version: 2` and a parallel verifier release.
- The envelope JSON shape may grow new optional fields; the only field a verifier must read is `license`.

---

## 10. Security considerations

- **Private key handling.** Signing keys live in the control panel's vault, encrypted at rest with a KMS master key. They never appear in license files, JWKS, or logs.
- **Replay.** The artifact is bearer-credential-shaped but it's installed on disk, not transmitted per request. Replay across customers is prevented by `aud` + `sub`; replay *for* the same customer is just normal use.
- **Tampering.** Any byte flip inside the JWT invalidates the Ed25519 signature. Tampering with the envelope metadata is detectable only by humans; verifiers ignore those fields.
- **Time manipulation.** A customer turning back the clock can extend an expired license. This is an accepted offline-mode risk; mitigate with shorter `exp` for high-trust deployments and online CRL polling where possible.
- **Algorithm confusion.** Verifiers MUST hard-code `EdDSA` and reject `alg: none`, `HS256`, etc. The starter does this; custom integrators must too.
