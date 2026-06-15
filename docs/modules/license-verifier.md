# Module: `license-verifier` — the customer-side OFFLINE verifier SDK

## Module overview

`license-verifier` is a small, **dependency-light, plain-Java SDK** (Java 21, no Spring) whose single job is to take a `.lic` file produced by the control-panel API and decide, **entirely offline**, whether it is a genuine, currently-valid license for *this* application — and, if so, hand back a clean, immutable `License` object the host app can query for plan, permissions, seats, feature flags, and expiry. "Offline" is the whole point: the customer runs the issuer's app inside *their own* Docker container, possibly air-gapped, and must be able to boot and enforce entitlements without ever calling home. Trust is therefore rooted in **public-key cryptography**, not in a network round-trip: the control panel signs each license as an Ed25519-signed JWS (JWT) with `typ=license+jwt`, and this SDK verifies that signature against a bundled/served **JWKS** (the issuer's public keys), then enforces the registered claims (`exp`, `nbf`, `aud`, `iss`) and an optional **revocation** check.

The module is deliberately framework-free so it can be embedded anywhere; the `license-verifier-spring-boot-starter` (documented separately) wraps this SDK in Spring auto-configuration, supplies the `RevocationChecker`, drives the `verifyAllowingExpired` / READ_ONLY grace logic, and schedules CRL/JWKS refreshes. Everything in *this* module is callable from a `public static void main` with nothing but the SDK on the classpath.

Key dependencies (from `license-verifier/pom.xml`):

| Dependency | Why it is here |
|---|---|
| `com.nimbusds:nimbus-jose-jwt` | JOSE/JWT parsing, `SignedJWT`, `JWSVerifier`, `JWKSet`, `OctetKeyPair`, `Ed25519Verifier`. |
| `com.google.crypto.tink:tink` | **Runtime** crypto backend Nimbus needs for EdDSA; Nimbus marks it optional, so the SDK declares it explicitly. Without it, Ed25519 verification fails at runtime. |
| `com.fasterxml.jackson.*` | Parsing the optional JSON **envelope** wrapper around the raw JWT. |
| `org.slf4j:slf4j-api` | Logging in the URL-backed JWKS provider only (no logging on the verify hot path). |
| `org.projectlombok:lombok` (`provided`) | Generates the `License` value object (`@Value @Builder`). Compile-time only. |

---

## How it fits the bigger picture

```
 control-panel-api  ──issues──▶  license.lic (Ed25519 JWS, typ=license+jwt)
        │                                  │
        │ publishes JWKS (public keys)     │ shipped to customer, dropped in container
        │ publishes signed CRL (typ=crl+jwt)
        ▼                                  ▼
   JWKS endpoint / file        ┌───────────────────────────────────────┐
        │                      │  CUSTOMER's Docker app                  │
        └──── consumed by ───▶ │  license-verifier-spring-boot-starter   │
                               │      └── wraps ──▶ THIS MODULE          │
                               │             LicenseVerifier.verify(...)  │
                               │             CrlVerifier.verify(...)      │
                               │             ▶ License (plan/perms/...)   │
                               └───────────────────────────────────────┘
```

- The **control-panel-api** is the *issuer*: it holds the Ed25519 **private** key, mints license JWTs and signed CRLs, and publishes the matching **public** JWKS.
- This module is the *verifier*: it never sees a private key, never needs the issuer to be reachable at verify-time (JWKS can be a bundled classpath file), and turns the opaque token into a typed `License`.
- The **Spring Boot starter** is the integration layer: it owns the lifecycle (load license at startup, refresh JWKS/CRL on a schedule, expose a `RevocationChecker`, decide READ_ONLY-on-expiry policy) and delegates the actual cryptographic decision to the classes documented here.
- The **sample-docker-app** demonstrates a consumer end to end.

Security posture is **fail-closed and least-trust**: unknown algorithm → reject; missing/wrong `typ` → reject; unknown `kid` → reject; non-Ed25519 key → reject; bad signature → reject; missing `exp` → reject; wrong `aud`/`iss` → reject; revoked or *non-operational* revocation checker → reject. The single deliberate relaxation, `verifyAllowingExpired`, tolerates **only** a present-but-past `exp` so a restarted container can boot into a READ_ONLY grace instead of failing to start.

---

## Package layout

```
com.example.licenseverifier
├── LicenseVerifier.java        # entry point: parse + verify a license JWS → License
├── License.java                # immutable verified-license value object (Lombok @Value)
├── LicenseEnvelope.java        # optional JSON wrapper around the raw JWT
├── PublicKeyProvider.java      # JWKS source abstraction (+ Static / Url providers, JwkProvider SPI)
├── JwksParser.java             # JWKS JSON → keyed JWK / PublicKey maps
├── Ed25519Verifiers.java       # shared kid→Ed25519Verifier resolution (used by both verifiers)
├── CrlVerifier.java            # parse + verify a signed CRL JWS → RevocationList
├── RevocationChecker.java      # SPI the verifier consults for revocation (default none())
├── RevocationList.java         # immutable parsed CRL (issuer, issuedAt, nextUpdate, revoked jtis)
└── exceptions/
    ├── LicenseException.java                 # base RuntimeException for all failures
    ├── LicenseFileMalformedException.java     # structural / parse / type errors
    ├── LicenseSignatureInvalidException.java  # alg/signature/key-shape failures
    ├── LicenseExpiredException.java           # past exp (or missing exp)
    ├── LicenseNotYetValidException.java       # nbf in the future
    ├── LicenseAudienceMismatchException.java  # aud doesn't contain expected audience
    ├── LicenseIssuerMismatchException.java    # iss doesn't equal expected issuer
    ├── LicenseKidUnknownException.java        # kid not in the known JWKS
    └── LicenseRevokedException.java           # jti revoked OR checker not operational
```

---

## The end-to-end verify flow (read this first)

`LicenseVerifier.verify(content)` runs these steps **in this order** (order matters: cheap structural checks before crypto, crypto before claim checks, revocation last):

```
1. content blank?                      → LicenseFileMalformedException
2. starts with '{'? unwrap envelope    → LicenseFileMalformedException (bad JSON / missing license)
3. SignedJWT.parse(jwt)                → LicenseFileMalformedException (not a JWS)
4. header.alg == EdDSA?                → LicenseSignatureInvalidException   (ALG CONFINEMENT)
5. header.typ == "license+jwt"?        → LicenseFileMalformedException      (TYP CONFINEMENT)
6. header.kid present?                 → LicenseFileMalformedException
7. resolve verifier for kid           → LicenseKidUnknownException / LicenseSignatureInvalidException
8. signedJwt.verify(verifier)         → LicenseSignatureInvalidException   (SIGNATURE)
9. parse claims                       → LicenseFileMalformedException
10. exp present & (allowExpired || not past+skew); nbf not in future-skew
                                       → LicenseExpiredException / LicenseNotYetValidException
11. aud contains expected audience     → LicenseAudienceMismatchException
12. iss == expected issuer (if set)    → LicenseIssuerMismatchException
13. revocation: operational? jti not revoked?
                                       → LicenseRevokedException
14. map claims → License               → returns License
```

Every failure throws a subclass of `LicenseException` (an unchecked `RuntimeException`), so a caller can `catch (LicenseException)` once and branch on the subtype, or catch a specific subtype (e.g. `LicenseExpiredException` to enter a grace period).

---

# File-by-file documentation

## `LicenseVerifier.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/LicenseVerifier.java`

The heart of the module: a `final`, immutable, builder-constructed verifier. One instance is configured once (with a key provider, expected audience, optional issuer, clock skew, clock, and revocation checker) and is then **thread-safe and reusable** for any number of `verify(...)` calls — there is no per-call mutable state.

### Configuration fields (all `final`)

| Field | Source / default | Purpose |
|---|---|---|
| `keyProvider` (`PublicKeyProvider`) | required | Supplies the issuer's public keys for signature verification. |
| `audience` (`String`) | required | The token's `aud` **must contain** this value. This is what binds a license to *this* app deployment. |
| `issuer` (`String`) | optional (nullable) | If set, the token's `iss` must equal it exactly; if `null`, the issuer check is skipped. |
| `clockSkew` (`Duration`) | default `Duration.ZERO` | Tolerance applied symmetrically to `exp` and `nbf` to absorb clock drift between issuer and consumer. |
| `clock` (`Clock`) | default `Clock.systemUTC()` | The time source for temporal checks. **Injectable for tests** (`Clock.fixed(...)`). |
| `revocationChecker` (`RevocationChecker`) | default `RevocationChecker.none()` | Consulted after all other checks; see step 13. |

Two static constants:
- `EXPECTED_TYP = "license+jwt"` — the only accepted JOSE `typ`.
- `MAPPER` — a shared `ObjectMapper` used solely to parse the optional envelope.

### Public API

| Method | What it does |
|---|---|
| `static Builder builder()` | Entry point to construct a verifier. |
| `License verify(String licenseFileContent)` | Full strict verification; **rejects expired**. |
| `License verify(Path path)` | Reads the file UTF-8 and delegates to `verify(String)`; IO errors become `LicenseFileMalformedException`. |
| `License verifyAllowingExpired(String content)` | Same as `verify` **except a present-but-past `exp` does not throw**. Everything else (signature, alg, typ, kid, aud, iss, nbf, revocation, *and* a missing `exp`) is still enforced. |
| `License verifyAllowingExpired(Path path)` | File-reading variant of the above. |

**Why `verifyAllowingExpired` exists (important design note):** a customer container that has been running fine can be restarted *after* its license technically expired (e.g. renewal is in flight, or the box was offline over a weekend). A pure fail-closed `verify` would refuse to boot, taking the whole app down. `verifyAllowingExpired` lets the starter load the still-cryptographically-valid license and transition into a **READ_ONLY** grace mode (driven by `app.license.read-only-on-expiry=true` in the starter) instead of crash-looping. Crucially it does **not** weaken any other guarantee: a forged signature, wrong audience, or *missing* `exp` are all still rejected. The returned `License` still carries the real (past) `expiresAt`, and `License.isExpired(clock)` will report `true` — so the **caller** decides what an expired license is allowed to do; the SDK does not silently pretend it is valid.

### Private control flow

`extractJwt(String content)` — **envelope unwrapping.** If `content` starts with `{`, it is treated as a `LicenseEnvelope` JSON and the inner `license` field (the compact JWS) is extracted and trimmed; a missing/blank `license` field or invalid JSON throws `LicenseFileMalformedException`. Otherwise the content is assumed to already be a bare compact JWS and returned as-is. The trim happens in `verify` before this, and the inner license is trimmed again. Note the `catch (LicenseFileMalformedException e) { throw e; }` re-throw guard so the more specific "missing license field" message isn't swallowed by the generic JSON catch.

`verifyJwt(String jwt, boolean allowExpired)` — the actual pipeline (steps 3–14 above). Highlights and the *why* behind each guard:

- **Algorithm confinement (step 4).** `if (header.getAlgorithm() == null || !JWSAlgorithm.EdDSA.equals(header.getAlgorithm()))` → reject. This is the classic JWT defense: the verifier dictates the algorithm; it never trusts the token's `alg` to choose a verification strategy. In particular `alg: none` and any `RS*/HS*/ES*` token is rejected outright, closing algorithm-substitution attacks.
- **`typ` confinement (step 5).** The header `typ` must be present **and** equal (case-insensitively) to `license+jwt`. A **missing** `typ` is rejected too — this is a deliberate, subtle defense documented in-code: the same Ed25519 key also signs CRLs (`typ=crl+jwt`) and potentially other token types; an attacker who could obtain a same-key token of a *different* type must not be able to strip the `typ` header and have it accepted as a license. (The `CrlVerifierTest`/`LicenseVerifierTest` cross-type tests prove both directions: a `license+jwt` is rejected by the CRL verifier and a `crl+jwt` is rejected by the license verifier.)
- **`kid` required (step 6).** A blank/absent `kid` is malformed; the verifier must know *which* key to use and never falls back to "try them all".
- **Verifier resolution (step 7).** Delegated to `Ed25519Verifiers.resolve(keyProvider, kid)` (see that file). Unknown kid → `LicenseKidUnknownException`; known-but-wrong-shape key → `LicenseSignatureInvalidException`.
- **Signature check (step 8).** `signedJwt.verify(verifier)`. A `false` result throws `LicenseSignatureInvalidException("...signature is invalid")`. Any *exception* thrown by Nimbus during verification is wrapped into the same exception type (with the original message preserved if it was already a `LicenseSignatureInvalidException`). This means a caller never leaks a raw Nimbus/JCA exception.
- **Claims** (`getJWTClaimsSet`) parse failure → `LicenseFileMalformedException`.
- Then `validateTemporalClaims`, `validateAudience`, `validateIssuer`, `checkRevocation`, then `toLicense`.

`validateTemporalClaims(claims, allowExpired)`:
- `now = Instant.now(clock)`.
- **`exp` is mandatory.** A `null` `exp` always throws `LicenseExpiredException("...missing a mandatory exp claim", null)` — even when `allowExpired` is `true`. The rationale in-code: a license with no expiry is *malformed*, not "expired", and an unbounded license is a security risk; tolerating expiry must not become tolerating no-expiry.
- **Expiry check:** `if (!allowExpired && now.isAfter(expInstant.plus(clockSkew)))` → `LicenseExpiredException(..., expInstant)`. Note the skew is **added** to `exp`, giving a grace window of `clockSkew` past the real expiry (a 10-minute exp with 5-minute skew verifies fine 2 minutes after exp — see `clock_skew_tolerates_slight_expiration`).
- **`nbf` check (optional claim):** if present, `if (now.isBefore(nbfInstant.minus(clockSkew)))` → `LicenseNotYetValidException`. Skew is **subtracted** from `nbf`, so a slightly-fast consumer clock won't reject a just-activated license. `nbf` is enforced even under `allowExpired` (a not-yet-valid license is not the same as an expired one).

`validateAudience(claims)`: the token's `aud` is a **list**; verification requires that list to *contain* the configured `audience`. A `null` audience or a list missing the expected value throws `LicenseAudienceMismatchException(expected, actual)` (the exception captures both for diagnostics). This containment (not equality) semantics matches JWT `aud` being multi-valued — a single license could legitimately target several deployments.

`validateIssuer(claims)`: skipped entirely if `issuer == null`; otherwise exact `equals` match required against `iss`, else `LicenseIssuerMismatchException`.

`checkRevocation(String jti)` — **fail-closed revocation:**
```java
if (!revocationChecker.isOperational()) {
    throw new LicenseRevokedException(jti == null ? "<unknown>" : jti);   // FAIL CLOSED
}
if (jti != null && revocationChecker.isRevoked(jti)) {
    throw new LicenseRevokedException(jti);
}
```
The first guard is the security-critical one: if the checker cannot *prove* a jti is **not** revoked (e.g. the starter's CRL cache is stale or never loaded), the verifier rejects **every** license rather than risk honoring a revoked one. A license with no `jti` cannot be matched against the CRL, so it is treated as non-revoked *only if* the checker is operational — but note such a license can never *be* revoked either (nothing to match), which is why the issuer always stamps a `jti`.

`toLicense(JWTClaimsSet claims, String kid)` — **claim → `License` mapping.** Reads the registered + custom claims and builds an immutable `License`:
- `permissions` (a JSON string array) → unmodifiable `Set<String>` (empty set if absent).
- `features` (a JSON object) → unmodifiable `LinkedHashMap` copy (preserves order; empty map if absent).
- `customer` (a nested object with `org_name` / `contact_email`) → `License.Customer` record; tolerant of a missing object or missing fields (each becomes `null`).
- `seats` and `version` via `optionalInt` (default `0` when absent).
- `subscription_id`, `plan` via `stringClaim`.
- Standard claims (`jti`, `iss`, `sub`, `aud`, `iat`, `exp`, `nbf`) mapped through; the resolved `kid` is stamped onto the `License` so the consumer can tell which signing key was used.
- A `ClassCastException` anywhere (claims with unexpected JSON types) is caught and rethrown as `LicenseFileMalformedException("License claims have unexpected types")` — the method is `@SuppressWarnings("unchecked")` because of the `(List<String>)` / `(Map<String,Object>)` casts, and this catch is the safety net for them.

Helper `stringClaim` returns `value.toString()` (or `null`); `optionalInt` accepts a `Number` (via `intValue()`) or a parseable string, returning `null` on anything else (so a garbage `seats` value degrades to `0` rather than blowing up).

### `LicenseVerifier.Builder`

A standard fluent builder. Notable methods:
- `publicKeys(PublicKeyProvider)` — inject any provider directly.
- `publicKeysFromClasspath(String resource)` — loads a bundled JWKS from the classpath. It tries `LicenseVerifier.class.getResourceAsStream(resource)` first, then falls back to the **thread context classloader** (handling the leading-`/` normalization) — this dual lookup is what makes a bundled `jwks.json` resolvable both when the SDK is on the app classloader and when it is loaded by a framework classloader. A not-found resource throws `LicenseFileMalformedException`.
- `publicKeysFromUrl(URL, Duration refreshInterval)` and the `publicKeysFromUrl(URL)` overload (defaulting to a 24h refresh) — build a self-refreshing `UrlProvider`.
- `audience` / `issuer` / `clockSkew` / `clock` / `revocationChecker` — set the corresponding fields.
- `build()` — constructs the immutable verifier; the private constructor enforces the required fields (`keyProvider`, `audience`) via `Objects.requireNonNull` and applies the documented defaults for the rest.

**Gotchas for a new engineer:**
- `verify` vs `verifyAllowingExpired` is the *only* behavioral switch; everything else (alg/typ/kid/sig/aud/iss/nbf/revocation/missing-exp) is identical. Never reach for `verifyAllowingExpired` as a "lenient mode" — it relaxes *only* past-`exp`.
- The verifier instance is immutable and thread-safe; build it once (the starter does) and share it.
- Clock skew widens *both* ends; with a large skew you are accepting both slightly-early and slightly-expired tokens. Keep it small (minutes), as the tests do (5 minutes).
- A `null` issuer disables the issuer check — be intentional about that in production.

---

## `License.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/License.java`

The immutable, verified **value object** the SDK hands back. Built with Lombok `@Value` (all fields `private final`, generates getters, `equals`/`hashCode`/`toString`) and `@Builder`. A `License` only ever exists *after* successful verification, so possessing one means "this entitlement is cryptographically authentic" — interpreting *what it allows* (especially when expired) is the host app's job.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `jti` | `String` | Unique license id (the revocation key). |
| `issuer` | `String` | `iss` claim. |
| `subject` | `String` | `sub` — usually the org id. |
| `subscriptionId` | `String` | `subscription_id` custom claim. |
| `plan` | `String` | Plan code (e.g. `pro`). |
| `audience` | `List<String>` | `aud` list (default empty). |
| `permissions` | `Set<String>` | Granted permission codes (default empty). |
| `features` | `Map<String,Object>` | Feature flags / limits (default empty). |
| `seats` | `int` | Seat count (`0` if absent). |
| `issuedAt` / `expiresAt` / `notBefore` | `Instant` | `iat` / `exp` / `nbf` as instants. |
| `customer` | `Customer` | Nested org info (nullable). |
| `version` | `int` | License schema/content version (`0` if absent). |
| `kid` | `String` | The signing key id that verified this license. |

`@Builder.Default` is applied to `audience`, `permissions`, and `features` so a license built without them gets immutable empties rather than `null` — but note collection emptiness is the builder's concern; the verifier already passes unmodifiable copies.

### Methods

- `boolean hasPermission(String code)` — null-safe membership test against `permissions`. The primary entitlement gate a host app calls.
- `<T> T feature(String key, Class<T> type)` — typed accessor for a feature flag. It returns `null` if absent, casts directly if the stored value is already an instance of `type`, and **coerces numbers**: a JSON number stored as e.g. `Integer`/`Long`/`Double` can be requested as `Integer`/`Long`/`Double` (via `intValue()`/`longValue()`/`doubleValue()`), and anything can be requested `as String` via `toString()`. This matters because JSON parsing may not preserve the exact numeric box type you expect — `feature("max_users", Integer.class)` works even if Jackson handed back a `Long`. Returns `null` (rather than throwing) on an unsupported type request.
- `boolean isExpired(Clock clock)` — `expiresAt == null ? false : !now.isBefore(expiresAt)`. The double-negative means "expired" includes the exact instant `now == expiresAt` (i.e. expiry is inclusive). A clock is passed in (not `Instant.now()`) so callers can test deterministically and stay consistent with the verifier's clock.
- `Status status(Clock clock)` — convenience tri-state: `NOT_YET_VALID` if `now < notBefore`, else `EXPIRED` if `now >= expiresAt`, else `ACTIVE`. Mirrors the verifier's temporal logic but **without clock skew** — it is a point-in-time snapshot for display/policy, not the gate.

Explicit lowercase accessors (`plan()`, `jti()`, `keyId()`, `expiresAt()`, `permissions()`, `features()`) are declared alongside the Lombok `getPlan()` etc. — these record-style names give the starter/consumers a cleaner API surface (and `keyId()` aliases the `kid` field).

### Nested types

- `record Customer(String orgName, String contactEmail)` — immutable org descriptor; either field may be `null`.
- `enum Status { ACTIVE, EXPIRED, NOT_YET_VALID }`.

**Gotcha:** `isExpired`/`status` take a `Clock` deliberately; don't be tempted to add a no-arg overload that calls `Instant.now()` — passing the clock keeps expiry decisions testable and consistent with however the verifier was configured.

---

## `LicenseEnvelope.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/LicenseEnvelope.java`

A small Jackson `record` describing the **optional human-friendly JSON wrapper** a `.lic` file may use instead of being a bare compact JWS. The control panel can ship a file like:

```json
{
  "license": "<compact-JWS>",
  "issued_at": "2026-06-01T10:00:00Z",
  "customer": "Acme Corp",
  "plan": "Pro",
  "expires_at": "2027-06-01T10:00:00Z",
  "notes": "Drop this file at /etc/app/license.lic."
}
```

Only the `license` field is load-bearing — `LicenseVerifier.extractJwt` pulls it out and ignores the rest, which exist purely so a human can eyeball the file. The fields map snake_case JSON (`issued_at`, `expires_at`) with camelCase `@JsonAlias` fallbacks, and the record is annotated `@JsonIgnoreProperties(ignoreUnknown = true)` so adding new envelope fields later won't break old verifiers.

**Critical security note:** the envelope's `plan`/`expires_at`/`customer` are **untrusted decoration**. The verifier *never* reads entitlement data from the envelope — only from the signed JWT inside `license`. An attacker can freely edit the envelope's `plan` to `"enterprise"`; it changes nothing, because the resulting `License` is built entirely from verified JWT claims.

---

## `PublicKeyProvider.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/PublicKeyProvider.java`

The abstraction over **where the issuer's public keys come from**, plus two built-in implementations and a package-private SPI. This is what makes "offline" tunable: keys can be a static bundled file (truly offline) or a periodically-refreshed URL (online, for rotation).

### Interface `PublicKeyProvider`

| Method | Contract |
|---|---|
| `Optional<PublicKey> findByKid(String kid)` | The JCA `PublicKey` for a kid, if exportable. |
| `Set<String> knownKids()` | The kids currently known (used to build helpful "unknown kid" errors). |
| `void refresh()` | Re-load keys (no-op for static). |

Static factories:
- `fromJwks(InputStream)` → a `StaticProvider` parsed from a JWKS stream.
- `fromJwksUrl(URL, Duration)` → a `UrlProvider` that is **eagerly refreshed once and started** before being returned (so a misconfigured URL fails fast at construction, not silently later).
- `exportPublicKey(JWK)` → static helper that converts a JWK to a JCA `PublicKey` *only* for asymmetric types that support it (RSA/EC), returning `null` otherwise.

### The Ed25519 export caveat (read this — it explains a lot of the code)

Nimbus **cannot** export an Ed25519/X25519 `OctetKeyPair` to a `java.security.PublicKey` ("Export to java.security.PublicKey not supported"). Since this whole system uses **Ed25519**, the `findByKid`/`PublicKey`-map path would be empty for the keys that actually matter. The module solves this with a second, package-private interface:

```java
interface JwkProvider {                 // implemented by StaticProvider and UrlProvider
    Optional<JWK> findJwkByKid(String kid);
}
```

`Ed25519Verifiers.resolve` *prefers* this `JwkProvider` path, getting the raw Ed25519 `OctetKeyPair` straight from the JWKS without lossy conversion. The `findByKid`/`PublicKey` path is a fallback for *custom* (third-party) providers that only expose a `PublicKey`. So in normal operation the `publicKeys` map is empty for Ed25519 and that is **fine** — the JWKs are retained and used directly.

### `StaticProvider` (offline, immutable)

Constructed from `Map<String,JWK>`. It:
- defensively copies the map and throws `LicenseFileMalformedException("JWKS contained no keys")` if empty;
- eagerly exports each JWK to a `PublicKey` *where possible* (RSA/EC), keeping the JWK regardless — for Ed25519 the export yields `null` and is simply omitted from the `publicKeys` map (non-fatal, as explained above);
- stores both maps unmodifiable;
- `refresh()` is a no-op (static keys never change);
- implements `findJwkByKid` (the preferred resolution path).

This is the truly-offline option: bundle a `jwks.json` on the classpath and the verifier never touches the network.

### `UrlProvider` (online, self-refreshing, thread-safe)

For deployments that want **key rotation** without redeploying. Holds:
- the JWKS `url`, the `refreshInterval`, a 10s-connect-timeout `HttpClient`;
- two `AtomicReference<Map<...>>` (jwks + publicKeys) for lock-free reads — `findByKid`/`findJwkByKid`/`knownKids` read the current snapshot without synchronization;
- a single-thread **daemon** `ScheduledExecutorService` named `license-jwks-refresh`.

`start()` schedules `refresh()` with fixed delay (floored at 1000ms so a tiny interval can't hammer the endpoint), and swallows refresh exceptions so a transient outage doesn't kill the scheduler.

`refresh()` semantics — **fail-soft on refresh, fail-fast on first load:**
- GETs the URL (15s request timeout); non-2xx → `IOException`.
- Parses with `JwksParser.parseJwks`. **A response with 0 keys is ignored** (logs a warning, keeps previous keys) — guarding against an endpoint that briefly returns an empty set.
- On success, atomically swaps in the fresh jwks + exported PublicKeys.
- `InterruptedException` re-sets the interrupt flag and keeps previous keys.
- For any other failure: **if we have no keys yet** (initial load), it throws `LicenseFileMalformedException("Initial JWKS fetch ... failed")` — boot must fail rather than run with no trust anchors. If we *do* have keys, it logs and **keeps the previous keys** so a flaky endpoint doesn't break a running app.

**Concurrency/lifecycle gotchas:** the scheduler thread is a daemon, so it won't keep the JVM alive, but the `UrlProvider` has **no `close()`/shutdown** — in long-lived apps that's fine (one thread for the app's lifetime); be aware if you create providers dynamically. Reads are always consistent because of the `AtomicReference` snapshot swap.

---

## `JwksParser.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/JwksParser.java`

A stateless utility (private constructor) that turns JWKS JSON into keyed maps. Two layers:

- `parseJwks(...)` (package-private, returns `Map<String, JWK>`) is the **canonical** parse used internally: it calls `JWKSet.parse`, iterates keys, and **requires every entry to have a non-blank `kid`** (else `LicenseFileMalformedException("JWKS entry is missing 'kid'")`) — kid-keyed lookup is mandatory throughout the SDK. Any parse failure is wrapped as `LicenseFileMalformedException("Failed to parse JWKS JSON")`, with the existing-malformed re-throw guard so the specific "missing kid" message survives.
- `parse(...)` (public, returns `Map<String, PublicKey>`) wraps `parseJwks` and exports each JWK via `PublicKeyProvider.exportPublicKey`, **dropping** entries that can't export (Ed25519). This public form is a convenience for callers that genuinely want JCA `PublicKey`s; the verifier itself uses the JWK-preserving path.
- `readAll(InputStream)` reads UTF-8 bytes; a `null` stream or IO error → `LicenseFileMalformedException`.

**Gotcha:** because `toPublicKeys` silently omits Ed25519 keys, `JwksParser.parse(...)` will return an *empty* map for an all-Ed25519 JWKS — that is expected and is exactly why `StaticProvider`/`Ed25519Verifiers` keep and prefer the raw JWKs.

---

## `Ed25519Verifiers.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/Ed25519Verifiers.java`

A package-private utility (private constructor) holding the **shared kid→`JWSVerifier` resolution** used by *both* `LicenseVerifier` and `CrlVerifier`. Centralizing it guarantees the two paths can never diverge in how they treat key shapes — a real concern, since a license and a CRL are both Ed25519 JWS signed by the same keys.

`static JWSVerifier resolve(PublicKeyProvider keyProvider, String kid)` — two paths:

1. **Preferred (`JwkProvider`) path.** If the provider implements `PublicKeyProvider.JwkProvider` (the built-in Static/Url providers do), fetch the original `JWK` by kid:
   - kid absent → `LicenseKidUnknownException(kid, knownKids)`;
   - JWK present and is an `OctetKeyPair` on `Curve.Ed25519` → build and return `new Ed25519Verifier(okp)` (wrapping any build failure as `LicenseSignatureInvalidException`);
   - JWK present but **not** an Ed25519 OKP → `LicenseSignatureInvalidException("...not an Ed25519 OctetKeyPair")`. This is the **key-type confinement**: even a syntactically valid RSA/EC key in the JWKS cannot be used to verify a license.
2. **Fallback (custom-provider) path.** Only a `PublicKey` is available: look it up (`orElseThrow → LicenseKidUnknownException`), require it to be an `EdECPublicKey` (else `LicenseSignatureInvalidException`), then **reconstruct** an `OctetKeyPair` from the raw key bytes and build an `Ed25519Verifier`.

`static Base64URL encodeEd25519PublicKey(PublicKey)` — extracts the 32 raw Ed25519 bytes from a JDK X.509 `SubjectPublicKeyInfo` encoding. As the in-code comment documents, the JDK encoding for Ed25519 is always 44 bytes = a fixed 12-byte ASN.1 header `{30 2A 30 05 06 03 2B 65 70 03 21 00}` followed by the 32 raw key bytes per RFC 8032. The method takes the **last 32 bytes** (`Arrays.copyOfRange(encoded, len-32, len)`) rather than hard-coding the header length — robust to header variations — and guards against an encoding shorter than 32 bytes (`LicenseSignatureInvalidException`).

**Gotcha:** the "last 32 bytes" trick is specific to Ed25519's fixed-size key; do not reuse this helper for other curves. The `EdECPublicKey` instanceof check upstream is what keeps that contract.

---

## `CrlVerifier.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/CrlVerifier.java`

Parses and cryptographically verifies a **signed Certificate-Revocation-List JWS** (`typ=crl+jwt`), producing a `RevocationList`. The CRL is how the issuer revokes individual licenses out-of-band; the starter fetches/caches it and feeds a `RevocationChecker` backed by the result. The CRL is signed by the **same JWKS** as licenses, so an attacker can't forge "license X is *not* revoked" or push a bogus empty CRL.

Constructor `CrlVerifier(PublicKeyProvider keyProvider, String expectedIssuer)` — same key provider as the license verifier; `expectedIssuer` may be `null` to skip the issuer check.

`RevocationList verify(String crlJws)` — mirrors the license pipeline closely:
1. blank → `LicenseFileMalformedException`.
2. `SignedJWT.parse(trimmed)` → not-a-JWS → `LicenseFileMalformedException`.
3. alg must be `EdDSA` → else `LicenseSignatureInvalidException` (**alg confinement**).
4. `typ` must be present and equal `crl+jwt` → else `LicenseFileMalformedException` (**typ confinement** — a `license+jwt` token cannot masquerade as a CRL, proven by `rejects_wrong_typ`).
5. `kid` required.
6. `Ed25519Verifiers.resolve(keyProvider, kid)` — same shared resolution.
7. `signedJwt.verify(...)` → false → `LicenseSignatureInvalidException`; wrapped exceptions likewise.
8. parse claims → `LicenseFileMalformedException` on failure.
9. issuer: if `expectedIssuer != null` and `iss` differs → `LicenseIssuerMismatchException`.
10. read `issuedAt`, `nextUpdate`, `revoked`, return `new RevocationList(...)`.

Claim-reading helpers handle a Nimbus quirk explicitly documented in the code:
- `readEpochSeconds(claims, name, required)` — after `SignedJWT.parse`, Nimbus exposes NumericDate claims as `java.util.Date`, **not** `Number`. So this helper accepts a `Number` (epoch seconds), a `Date` (`.toInstant()`), or a parseable string; a missing **required** claim (`nextUpdate`) throws, a missing optional one returns `null`; a non-numeric value → `LicenseFileMalformedException`. `issuedAt` is read preferentially via the registered `getIssueTime()` accessor and falls back to `readEpochSeconds("iat", required=false)`.
- `readRevoked(claims)` — the `revoked` claim must be a JSON **array of strings**; absent → empty list; a non-array or a non-string element → `LicenseFileMalformedException`. This strictness prevents a malformed CRL from being silently parsed into a partial/garbage revocation set.

**Why this lives in the SDK (not the starter):** keeping CRL verification next to license verification, sharing `Ed25519Verifiers` and the exception types, ensures the revocation trust path is exactly as strong as the license trust path. The starter only orchestrates *fetching/caching*; the cryptographic decision is here.

**Gotcha:** `nextUpdate` is **required** — a CRL with no `nextUpdate` won't parse. That is intentional: `RevocationList.isStale` depends on `nextUpdate`, and a CRL with no freshness horizon can't be aged out, which would defeat fail-closed staleness.

---

## `RevocationChecker.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/RevocationChecker.java`

The SPI the `LicenseVerifier` consults at step 13. Two methods:

- `boolean isRevoked(String jti)` — is this license id revoked?
- `default boolean isOperational()` (default `true`) — does the checker currently have a *usable* view of revocation state? A fail-closed implementation returns `false` when it can't prove a jti is **not** revoked (stale/never-loaded CRL cache), and the verifier then rejects **everything** (see `checkRevocation`).

`static RevocationChecker none()` — returns a lazily-initialized singleton (via the `NoneHolder` idiom) that never revokes and is always operational. This is the default when no checker is configured, preserving the historical "no revocation" behavior for simple offline deployments that ship a bundled JWKS and don't run a CRL.

**Why the `isOperational` split matters:** without it, a checker that lost its CRL cache could only choose between "claim nothing is revoked" (fail-open — dangerous) or "claim everything is revoked" via `isRevoked` (which conflates "I don't know" with "definitely revoked"). The explicit operational flag lets the SDK distinguish *"I can't tell"* from *"this specific jti is revoked"*, and fail closed cleanly. The Spring starter supplies a CRL-backed implementation that returns `isOperational() == false` once its cached `RevocationList.isStale(...)`.

---

## `RevocationList.java`

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/RevocationList.java`

Immutable parsed CRL: `issuer`, `issuedAt`, `nextUpdate`, and the set of revoked jtis. Produced by `CrlVerifier.verify` and cached by the starter's revocation checker.

Construction defensively copies `revokedJtis` into an **unmodifiable `HashSet`** (a `null` set becomes empty) — verified by `RevocationListTest.revokedJtis_is_an_unmodifiable_defensive_copy`, so callers can't mutate the revocation set after the fact.

- Accessors `issuer()`, `issuedAt()`, `nextUpdate()`, `revokedJtis()`.
- `boolean isRevoked(String jti)` — null-safe set membership (`null` jti → `false`).
- `boolean isStale(Instant now, Duration maxStale)` — **the freshness gate.** Returns `true` (stale) if `nextUpdate == null` **or** `now.isAfter(nextUpdate.plus(maxStale))`. The comparison is **strict** (`isAfter`), so *exactly at* `nextUpdate + maxStale` the list is still considered fresh (proven by `isStale_false_exactly_at_..._boundary`). A `null` `nextUpdate` is always stale — though in practice `CrlVerifier` requires `nextUpdate`, this guards a hand-built `RevocationList`.

This `isStale` is what the starter's checker maps to `RevocationChecker.isOperational()`: once the cached CRL is stale, the checker reports non-operational and the verifier fails closed. So the chain is: **stale CRL → not operational → every license rejected** — exactly the desired fail-closed behavior for revocation.

---

## Exceptions package

**Path:** `license-verifier/src/main/java/com/example/licenseverifier/exceptions/`

All license failures are unchecked (`extends RuntimeException`) and share a common base so a host app can `catch (LicenseException)` once or pinpoint a subtype. Several carry **structured diagnostic data** (not just a message) so the starter can make policy decisions (e.g. read the past `expiresAt` to compute a grace deadline).

| Exception | Extends | Carries | Thrown when |
|---|---|---|---|
| `LicenseException` | `RuntimeException` | — | base type; never thrown directly. |
| `LicenseFileMalformedException` | `LicenseException` | — | empty/blank content, bad envelope JSON, not-a-JWS, missing/wrong `typ`, missing `kid`, claim-parse/type errors, JWKS parse/missing-kid, classpath resource not found, IO read failure. The catch-all "structurally wrong" error. |
| `LicenseSignatureInvalidException` | `LicenseException` | — | unsupported alg (not EdDSA), failed/`false` signature, non-Ed25519 key for a kid, bad Ed25519 key encoding. The "crypto doesn't check out" error. |
| `LicenseExpiredException` | `LicenseException` | `Instant getExpiresAt()` | `exp` in the past (beyond skew), or `exp` **missing** (with `null` instant). The starter reads `getExpiresAt()` to decide grace/READ_ONLY. |
| `LicenseNotYetValidException` | `LicenseException` | `Instant getNotBefore()` | `nbf` in the future (beyond skew). |
| `LicenseAudienceMismatchException` | `LicenseException` | `String getExpected()`, `List<String> getActual()` | token `aud` doesn't contain the configured audience. Builds its own descriptive message from both values. |
| `LicenseIssuerMismatchException` | `LicenseException` | `String getExpected()`, `String getActual()` | token `iss` ≠ expected issuer (license **or** CRL). |
| `LicenseKidUnknownException` | `LicenseException` | `String getKid()`, `Set<String> getKnownKids()` | the token's `kid` isn't in the JWKS. `getKnownKids()` aids debugging key rotation. |
| `LicenseRevokedException` | `LicenseException` | `String getJti()` | jti is in the revoked set **or** the `RevocationChecker` is not operational (fail-closed). |

**Gotcha:** `LicenseRevokedException` is thrown for *two distinct* reasons (actually-revoked vs. checker-not-operational). If you need to distinguish them in a host app, you can't from the exception alone — that conflation is intentional (both mean "do not honor this license right now"), but worth knowing when debugging a "why is my valid license rejected?" report — a stale CRL cache looks identical to a real revocation.

---

## Tests (behavioral spec — for reference, in `src/test`)

The tests are the executable specification for everything above and are worth reading to confirm intended behavior:

- **`LicenseVerifierTest`** — full claim extraction (raw JWT and envelope-wrapped), tampered signature/payload, expired, not-yet-valid, wrong audience, wrong issuer, unknown kid, malformed envelope, empty content, **missing `typ`** and **wrong `typ`** rejection, `verifyAllowingExpired` (returns expired-but-valid; still enforces signature/audience; still rejects **missing** `exp`), and clock-skew tolerance. It generates a fresh Ed25519 key per run and uses `Clock.fixed` for determinism.
- **`LicenseVerifierRevocationTest`** — revoked jti throws `LicenseRevokedException` (and the exception carries the jti); a populated checker passes a non-revoked license; a **non-operational** checker fails closed and rejects a valid license; the default `none()` never revokes.
- **`CrlVerifierTest`** — verifies a signed CRL and its claims, empty revoked list, issuer-check-disabled (`null` issuer), wrong/missing `typ` rejection, tampered signature/payload, unknown kid, issuer mismatch, blank/non-JWS input. Also demonstrates the cross-type confinement: a `license+jwt` is rejected as a CRL.
- **`RevocationListTest`** — `isRevoked` truth table (listed/unlisted/null/empty/null-set), unmodifiable defensive copy, and the full `isStale` boundary matrix (before `nextUpdate`, within grace, exactly at boundary = not stale, past grace = stale, `maxStale == 0`, and `nextUpdate == null` always stale).

---

## Quick-start: how a customer app uses this offline

```java
// 1. Build a verifier once (thread-safe, reusable). Keys bundled on the classpath ⇒ fully offline.
LicenseVerifier verifier = LicenseVerifier.builder()
        .publicKeysFromClasspath("/issuer-jwks.json")   // or .publicKeysFromUrl(url) for rotation
        .audience("docker-app-prod")                     // binds the license to THIS deployment
        .issuer("https://control-panel.example.com")     // optional but recommended
        .clockSkew(Duration.ofMinutes(5))                // absorb clock drift
        // .revocationChecker(crlBackedChecker)          // optional; starter supplies this
        .build();

// 2. Verify the .lic file at startup.
try {
    License license = verifier.verify(Path.of("/etc/app/license.lic"));
    if (license.hasPermission("export.pdf")) { /* enable feature */ }
    int maxUsers = license.feature("max_users", Integer.class);
} catch (LicenseExpiredException e) {
    // Optional grace: re-verify tolerating expiry and enter READ_ONLY.
    License readOnly = verifier.verifyAllowingExpired(Path.of("/etc/app/license.lic"));
    // ... run degraded; readOnly.isExpired(clock) == true
} catch (LicenseException e) {
    // Forged/tampered/wrong-audience/revoked → refuse to start.
}
```

The crucial properties to internalize: **no network is required** (with a bundled JWKS), the verifier **dictates the algorithm and token type** (no `alg`/`typ` confusion), entitlements come **only** from the signed JWT (never the envelope), and the system **fails closed** on every ambiguity — including a revocation checker that can't currently see a fresh CRL.
