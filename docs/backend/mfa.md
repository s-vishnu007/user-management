# Package `com.example.cp.mfa` — TOTP Multi-Factor Authentication

## Module overview

This package implements **time-based one-time-password (TOTP, RFC 6238) multi-factor authentication** for human operators of the control panel. It owns the full lifecycle of a second factor: **enrollment** (generate a secret, hand it to an authenticator app), **confirmation** (prove the app is set up before turning MFA on), **verification during login** (the second step of a two-step sign-in), and **disable**. The TOTP shared secret is never stored in the clear — it is encrypted at rest with AES-GCM via the shared `KeyEncryptor` and only the plaintext base32 secret is returned exactly once, at enrollment time. Two security properties get special attention here: **replay protection** (a code, once accepted, can never be re-accepted — even inside the verifier's clock-skew window) implemented via a monotonic `last_accepted_step` watermark, and the **MFA challenge token** — a short-lived, purpose-stamped HS256 JWT that proves "the password step succeeded" and bridges step 1 and step 2 of login without ever being usable as a real session.

The package is small and self-contained — four files:

| File | Type | Responsibility |
|------|------|----------------|
| `UserMfa.java` | JPA `@Entity` | One row per enrolled user: encrypted secret, enabled flag, replay watermark |
| `UserMfaRepository.java` | Spring Data repo | CRUD + existence/enabled checks keyed by `userId` |
| `MfaService.java` | `@Service` | All TOTP logic: enroll/confirm/verify/disable, challenge mint/parse, replay-safe code check |
| `MfaController.java` | `@RestController` | Self-service REST endpoints (`/api/v1/auth/mfa/{enroll,verify,disable}`) for the logged-in user |

### How it fits the bigger picture

MFA sits at the boundary between **authentication** (`com.example.cp.auth`) and **secret-at-rest management** (`com.example.cp.keys`):

- **`AuthController` drives the two-step login.** On `POST /login`, after the password check, it asks `MfaService.isEnabled(userId)`. If MFA is on, it does **not** issue a session — instead it calls `MfaService.issueChallenge(...)` and returns `mfaRequired=true` plus a short-lived `mfaChallenge`. The browser then calls `POST /api/v1/auth/mfa/login` (which lives in `AuthController`, **not** in this package — it must be reachable without a session) with the challenge + the 6-digit code. `AuthController` calls `MfaService.parseChallenge(...)` to recover the user id, then `MfaService.verifyLoginCode(...)`, and only then mints the real session token. So this package supplies the *primitives* of step 2; `AuthController` orchestrates them.
- **`MfaController` handles self-service** (enroll / confirm / disable) for an already-authenticated user managing their own account.
- **`KeyEncryptor` (`com.example.cp.keys`)** encrypts/decrypts the TOTP secret. The secret column participates in the same versioned-KEK rotation machinery as signing keys, webhook HMAC secrets, and OIDC client secrets.
- **`SessionTokenService` (`com.example.cp.auth`)** and `MfaService` both sign HS256 JWTs **with the same `app.auth.session-secret`**, but use *different `purpose` claims* (`session` vs `mfa_challenge`). This deliberate sharing-of-key-but-not-of-meaning is the crux of why a challenge can never be promoted to a session (see "The MFA challenge token" below).

```
                       password OK + MFA enabled
  POST /login ───────────────────────────────────────►  MfaService.issueChallenge()
   (AuthController)                                            │  HS256, purpose=mfa_challenge, 5-min TTL
        ▲                                                      ▼
        │  mfaRequired=true, mfaChallenge=<jwt>  ◄──────────────
        │
        │  user types 6-digit code
        ▼
  POST /api/v1/auth/mfa/login  ──► parseChallenge() ─► verifyLoginCode() ─► SessionTokenService.issue()
   (AuthController)                  (recover uid)      (replay-safe)         (real session at last)
```

---

## `control-panel-api/src/main/java/com/example/cp/mfa/UserMfa.java`

### Responsibility
The JPA entity persisting one user's TOTP enrollment. The table is `user_mfa`, keyed by `user_id` (so a user can have **at most one** TOTP factor — there is no multi-device enrollment table here). The row is created in the disabled state when enrollment starts and flipped to enabled on first successful confirmation; disabling deletes the row entirely.

### Class `UserMfa`
A Lombok-annotated entity (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`). `@Builder.Default` is applied to `enabled` so a builder-constructed row without an explicit `enabled(...)` defaults to `false` rather than to Java's `boolean` default of `false` *without* the builder noticing — the annotation keeps the builder and field defaults consistent.

| Field | Column | Type | Meaning / notes |
|-------|--------|------|-----------------|
| `userId` | `user_id` (`@Id`, not null) | `UUID` | Primary key; **same** id space as the `users` table — one MFA row per user. |
| `secretEnc` | `secret_enc` (not null) | `byte[]` | The AES-GCM envelope (produced by `KeyEncryptor.encrypt`) wrapping the **base32-encoded** TOTP shared secret. Never the plaintext. |
| `enabled` | `enabled` (not null) | `boolean` | `false` while enrollment is pending; `true` once a code has been confirmed. Login only requires a code when this is `true`. |
| `lastAcceptedStep` | `last_accepted_step` (nullable) | `Long` | The 30-second TOTP time-step (`epochSeconds / 30`) of the **most recently accepted** code. `null` until the first accept. This is the **replay watermark** (see below). |
| `createdAt` | `created_at` (not null) | `OffsetDateTime` | When the (current) enrollment row was created. |

### Why `lastAcceptedStep` exists (the replay watermark)
A standard TOTP verifier accepts any code valid within ±1 time-step of "now" to tolerate clock skew — that's a ~90-second window in which the *same* code is valid. Without extra state, an attacker who observes a code (shoulder-surfing, a logged proxy, an intercepted form post) could replay it for up to ~90 seconds. `lastAcceptedStep` closes that hole: every accepted code records its step, and any future code whose step is `<= lastAcceptedStep` is rejected. Because TOTP steps advance monotonically with wall-clock time, this makes each successful authentication strictly consume "the newest step seen so far," so a given code is single-use.

### Gotchas
- The secret stored is the **base32 string's UTF-8 bytes**, then encrypted — not the raw key bytes. `MfaService` round-trips it as `new String(decrypt(...), UTF_8)` and feeds that base32 string straight back to the TOTP `CodeGenerator`. Don't "helpfully" base32-decode it anywhere.
- There is no `@Version` optimistic-lock column here. Concurrency of two simultaneous code submissions for the same user is discussed under `verifyAndAdvanceStep` — the replay guard is correct for the realistic case, but two truly-parallel transactions are serialized by the DB row, not by JPA versioning.

---

## `control-panel-api/src/main/java/com/example/cp/mfa/UserMfaRepository.java`

### Responsibility
Spring Data JPA repository for `UserMfa`, keyed by `UUID`. Adds three derived-query methods on top of the standard `JpaRepository` CRUD.

### Interface `UserMfaRepository extends JpaRepository<UserMfa, UUID>`

| Method | Returns | Purpose / why |
|--------|---------|---------------|
| `findByUserId(UUID userId)` | `Optional<UserMfa>` | Load the (single) enrollment row. Used by enroll (to overwrite a pending secret), confirm, and verify. Functionally equivalent to `findById` since `userId` *is* the `@Id`, but the explicit derived method reads clearly at call sites. |
| `existsByUserIdAndEnabledTrue(UUID userId)` | `boolean` | True **only** when the user has a row AND `enabled=true`. This is the single source of truth for "does login need a code?" — a pending (unconfirmed) enrollment returns `false`, so a half-finished enrollment never locks a user out. |
| `deleteByUserId(UUID userId)` | `void` | Disable = delete the row. Idempotent at the SQL level (deleting zero rows is fine). |

### Collaborators
Called exclusively by `MfaService`. Nothing else in the codebase should touch `user_mfa` directly — go through the service so encryption and the replay guard are always applied.

### Gotcha
`existsByUserIdAndEnabledTrue` is intentionally distinct from a plain `existsByUserId`. A user mid-enrollment has a row but `enabled=false`; treating that as "MFA on" would force them to produce a code during login before they'd ever confirmed the factor — locking them out. Always reason in terms of *enabled*, not *exists*.

---

## `control-panel-api/src/main/java/com/example/cp/mfa/MfaService.java`

### Responsibility
The brain of the package. It encapsulates every TOTP operation, the encryption round-trip, the replay-safe verification, and the challenge-token mint/parse. It deliberately exposes a small, intention-revealing API (`isEnabled`, `enroll`, `confirmEnrollment`, `verifyLoginCode`, `disable`, `issueChallenge`, `parseChallenge`) and keeps the cryptographic details private.

### TOTP parameters (constants)

| Constant | Value | Meaning |
|----------|-------|---------|
| `ALLOWED_TIME_PERIOD_DISCREPANCY` | `1` | ± this many 30s steps of clock-skew tolerance when verifying. Window = 3 steps (prev/now/next). Package-private (`static final int`) so tests can reference it. |
| `TIME_PERIOD_SECONDS` | `30` | TOTP period. |
| `ALGORITHM` | `HashingAlgorithm.SHA1` | HMAC hash. SHA1 is the de-facto default every authenticator app supports — and is stamped into the `otpauth://` URI so the app and server agree. |
| `DIGITS` | `6` | Code length. Also used as an input-length pre-check. |
| `CHALLENGE_PURPOSE` | `"mfa_challenge"` | `purpose` claim distinguishing a challenge from a session token. |
| `CHALLENGE_ISSUER` | `"control-panel"` | `iss` claim — same literal value as the session token's issuer. |
| `CHALLENGE_TTL` | `Duration.ofMinutes(5)` | Challenge lifetime: long enough to fetch and type a code, short enough to limit the replay surface. |

### Construction & injected collaborators

```java
public MfaService(UserMfaRepository repository,
                  KeyEncryptor keyEncryptor,
                  @Value("${app.signing.issuer:control-panel}") String issuerLabel,
                  @Value("${app.auth.session-secret:}") String sessionSecret)
```

- `repository` — persistence.
- `keyEncryptor` — AES-GCM encrypt/decrypt of the secret (`com.example.cp.keys.KeyEncryptor`).
- `issuerLabel` (config `app.signing.issuer`, default `control-panel`) — the human-readable issuer shown in the authenticator app, baked into the `otpauth://` URI. Blank/`null` falls back to `control-panel`.
- `sessionSecret` (config `app.auth.session-secret`) — the HMAC key for **both** signing and verifying the challenge JWT. Blank/`null` is coalesced to `""`.

The constructor also instantiates the TOTP primitives itself (not Spring beans): `DefaultSecretGenerator`, `SystemTimeProvider`, and `DefaultCodeGenerator(SHA1, 6)` from the `dev.samstevens.totp` library.

> **Security gotcha — the empty-secret trap.** Unlike `SessionTokenService` (which *fails fast* in its constructor if `app.auth.session-secret` is missing or `< 32` chars), `MfaService` silently coalesces a missing secret to `""`. In a correctly-configured deployment both beans read the *same* property, so `SessionTokenService`'s strict validation effectively guards `MfaService` too — the app won't start with a weak/absent session secret. But if you ever wire `MfaService` without `SessionTokenService` (e.g. a narrow test slice), be aware it will happily sign challenges with an empty HMAC key. The real protection lives in the auth package's startup check.

---

### `boolean isEnabled(UUID userId)` — `@Transactional(readOnly = true)`
Returns `true` iff `userId != null` and `existsByUserIdAndEnabledTrue`. This is what `AuthController` consults after a correct password to decide between "issue a session now" and "issue a challenge and demand a code." Null-safe so a not-yet-resolved user can't NPE the login path.

---

### `EnrollmentResult enroll(UUID userId, String accountLabel)` — `@Transactional`
Starts (or **restarts**) enrollment.

Flow:
1. `userId == null` → `401 unauthorized` (defensive; the controller already requires a session).
2. If MFA is **already enabled** → `409 conflict` ("disable it before re-enrolling"). You can't silently rotate the secret out from under an active factor.
3. Generate a fresh base32 secret via `secretGenerator.generate()`.
4. **Reuse** an existing row if present (`findByUserId(...).orElseGet(...)`) — so re-enrolling overwrites the *pending* secret instead of creating a duplicate-key conflict — otherwise build a new row with `createdAt = now`.
5. Encrypt the secret (`keyEncryptor.encrypt(secret.getBytes(UTF_8))`) into `secretEnc`, force `enabled=false`, backfill `createdAt` if missing, and `save`.
6. Build the `otpauth://` URI and return `EnrollmentResult(secret, uri)` — **the only time the plaintext secret leaves the service.**

Why restart-safe: a user who starts enrollment, never confirms, and starts again should just get a new secret on the same row. Forcing `enabled=false` on every enroll guarantees an in-progress re-enroll can't accidentally leave a previously-enabled factor on with a mismatched secret (though step 2 prevents reaching here while enabled anyway).

Edge case: the existing-row branch does **not** reset `lastAcceptedStep`. For a brand-new row it's `null`; for a re-enroll of a never-confirmed row it's also still `null`, so this is benign. (A confirmed/enabled row can't reach this code because of the step-2 conflict check.)

---

### `boolean confirmEnrollment(UUID userId, String code)` — `@Transactional`
Confirms a pending enrollment by verifying the user's first code, and flips `enabled=true`.

1. Load the row or throw `400 badRequest` ("No MFA enrollment in progress") — you can't confirm without having enrolled.
2. `verifyAndAdvanceStep(row, code)` — if `false`, return `false` immediately (no state change, controller turns this into `400 Invalid code`).
3. If not already enabled, set `enabled=true`.
4. `save` and return `true`.

**Idempotent** for an already-enabled user who presents a valid current code: it just re-saves with `enabled` unchanged and returns `true`. Note that even a successful confirm *advances the replay watermark*, because `verifyAndAdvanceStep` mutates `lastAcceptedStep` — so the code used to confirm can't immediately be reused to log in.

---

### `boolean verifyLoginCode(UUID userId, String code)` — `@Transactional`
The step-2-of-login primitive. Verifies a code for an **enabled** user.

1. Load the row; if absent **or** `!enabled` → return `false`. (A pending enrollment can't satisfy a login.)
2. `verifyAndAdvanceStep(row, code)`; on failure return `false`.
3. `save` (persisting the advanced watermark) and return `true`.

Writable transaction *by design* — the whole point is that a successful login verification consumes the time-step. `AuthController.mfaLogin` calls this only after `parseChallenge` has proven the password step succeeded.

---

### `void disable(UUID userId)` — `@Transactional`
`repository.deleteByUserId(userId)`. Deletes the enrollment row. Idempotent — deleting a non-existent row is a no-op. After this, `isEnabled` returns `false` and login no longer requires a code.

---

### `MfaChallenge issueChallenge(UUID userId, String email)`
Mints the short-lived bridge token for step 1 of login. **Not** `@Transactional` — it touches no database state.

Builds an HS256-signed JWT:

| Claim | Value | Purpose |
|-------|-------|---------|
| `iss` | `control-panel` (`CHALLENGE_ISSUER`) | Issuer pin; checked on parse. |
| `sub` | `userId.toString()` | The user the challenge is bound to. |
| `email` | `email` | Convenience for the client/audit. |
| `purpose` | `mfa_challenge` (`CHALLENGE_PURPOSE`) | **The critical claim** — marks this as a challenge, not a session. |
| `iat` / `exp` | now / now + 5 min | Lifetime. |
| `jti` | random UUID | Unique id per challenge. |

Signed with `MACSigner(sessionSecret.getBytes(UTF_8))`. Returns `MfaChallenge(serializedJwt, exp)`. A `JOSEException` is wrapped as `IllegalStateException("Failed to sign MFA challenge")` — a signing failure is a server misconfiguration, not a client error.

---

### `UUID parseChallenge(String challenge)`
Validates a challenge token and returns the bound `userId`. Throws `ApiException.unauthorized(...)` (401) on **any** failure. Steps, all of which must pass:

1. Non-null / non-blank → else "Missing MFA challenge".
2. `SignedJWT.parse` + `jwt.verify(MACVerifier(sessionSecret))` → bad signature ⇒ "Invalid MFA challenge".
3. `purpose == mfa_challenge` **and** `iss == control-panel` → else "Invalid MFA challenge".
4. `exp` present and not in the past → else "MFA challenge expired".
5. Return `UUID.fromString(sub)`.

`ParseException | JOSEException | IllegalArgumentException` (the last covers a malformed `sub` UUID) are all caught and collapsed into a generic `401 "Invalid MFA challenge"` — no internal detail leaks to the caller.

> **Why purpose + issuer matter (the load-bearing design).** The challenge JWT and the real session JWT are signed with the **same HMAC key**. If `parseChallenge` checked only the signature, an attacker who somehow obtained a *session* token could feed it to `/mfa/login`; conversely, a leaked challenge must never satisfy session parsing. Two symmetric guards prevent cross-use:
> - `MfaService.parseChallenge` requires `purpose=mfa_challenge`.
> - `SessionTokenService.parse` requires `purpose=session` *and* a present issuer.
>
> A session token has `purpose=session`, so it fails the challenge's purpose check; a challenge token has `purpose=mfa_challenge`, so it fails the session's purpose check. Same key, different meaning — the `purpose` claim is the type tag that keeps the two token universes disjoint.

---

### `private boolean verifyAndAdvanceStep(UserMfa row, String code)` — the replay-safe core

This is the most security-sensitive method in the package. It re-implements TOTP window matching by hand specifically so it can enforce the monotonic replay guard, which the off-the-shelf verifier cannot express (the library only answers "valid somewhere in the window," not "valid at *which* step").

Flow:

```
1. Reject null/blank code.
2. Trim; reject if length != 6 (DIGITS).                    // cheap structural pre-check
3. secret = UTF8(keyEncryptor.decrypt(row.secretEnc))       // base32 string
4. currentStep = floorDiv(timeProvider.getTime(), 30)
5. last = row.lastAcceptedStep
6. for step from (currentStep + 1) DOWN TO (currentStep - 1):   // newest first
       if (last != null && step <= last) continue;             // REPLAY GUARD
       expected = codeGenerator.generate(secret, step)
       if (constantTimeEquals(expected, candidate)) {
           row.lastAcceptedStep = step;                        // advance watermark
           return true;
       }
   return false;
```

Key points and *why*:

- **Newest step first.** The loop counts **down** from `currentStep + 1` so that if a code happens to be valid at more than one candidate step (rare but possible at boundaries), it consumes the *latest* matching step — maximizing how far the watermark advances and shrinking the future replay window.
- **The replay guard** (`step <= last → skip`) is the whole reason this isn't just a library call. Any step at or before the last accepted one is unreachable, so an observed code cannot be replayed even within the skew window.
- **Constant-time comparison.** `constantTimeEquals` uses `MessageDigest.isEqual` over UTF-8 bytes, avoiding an early-exit timing side channel that a naive `String.equals` would leak. (Length is already pinned to 6 by step 2, so length-leak isn't a concern, but the comparison stays constant-time regardless.)
- **`CodeGenerationException` → `return false`.** A generation error is treated as "not a match" rather than propagating — fail closed.
- **Caller persists.** This method *mutates* `row.lastAcceptedStep` but does **not** save; `confirmEnrollment` / `verifyLoginCode` call `repository.save(row)` after a `true`. On a `false` return nothing is mutated, so there's nothing to persist.

> **Concurrency note.** There is no `@Version` on `UserMfa`. Two genuinely-parallel verifications of the *same* code for the *same* user could, in principle, both read `last`, both match the same step, and both return `true` before either saves — a theoretical double-accept. In practice both run inside `@Transactional` methods touching the same primary-key row, so the database serializes the writes; and the realistic threat model (an attacker replaying a code *after* observing a legitimate login) is fully covered because the legitimate login has already advanced and committed the watermark. If you ever need to harden against the simultaneous-submission race, add `@Version` (optimistic lock) or a `SELECT ... FOR UPDATE` on the row.

---

### `private String otpAuthUri(String secret, String accountLabel)`
Builds the standard Key-URI-Format provisioning string consumed by Google Authenticator / Authy / 1Password / etc.:

```
otpauth://totp/<issuer>:<account>?secret=<base32>&issuer=<issuer>&algorithm=SHA1&digits=6&period=30
```

- Label is `issuer:account`; a blank/null `accountLabel` defaults to `"user"`.
- `issuer` is URL-encoded and repeated as a query parameter, per spec.
- `algorithm`, `digits`, `period` are emitted explicitly **so the app's generator exactly matches the server's verifier** — these mirror `ALGORITHM`, `DIGITS`, `TIME_PERIOD_SECONDS`. (Subtle: the `secret` value itself is intentionally *not* URL-encoded — base32 alphabet has no characters needing escaping, and double-encoding would corrupt it.)

`enc(String)` is a tiny `URLEncoder.encode(s, UTF_8)` helper used for the label parts and issuer.

---

### Nested records
- `public record EnrollmentResult(String secret, String otpAuthUri)` — the one-time enrollment payload (plaintext base32 secret + provisioning URI). Surfaced to the client via `MfaController.EnrollResponse`.
- `public record MfaChallenge(String challenge, Instant expiresAt)` — the step-1 challenge token and its expiry, consumed by `AuthController` to populate `LoginResponse.mfaChallenge(...)`.

---

## `control-panel-api/src/main/java/com/example/cp/mfa/MfaController.java`

### Responsibility
REST surface for **self-service** MFA management by the authenticated user, mounted at `/api/v1/auth/mfa`. Every endpoint acts on **the caller's own account** — the user id always comes from `SecurityUtils.requireUser()`, never from a path/body parameter, so there's no IDOR surface (a user can only enroll/disable their *own* MFA).

> Note: the *login completion* endpoint `POST /api/v1/auth/mfa/login` is **not** here — it lives in `AuthController`, because by definition it must be reachable by a user who does **not** yet have a session (they're mid-login). This controller's three endpoints all require an authenticated session.

### Class `MfaController`
Constructor-injects `MfaService`. Endpoints:

#### `POST /enroll → EnrollResponse enroll()`
1. `me = SecurityUtils.requireUser()` (401 if no session).
2. `result = mfaService.enroll(me.userId(), me.email())` — the email becomes the `otpauth` account label.
3. Audit: `AuditContext.set("auth.mfa.enroll")`, target `user:<id>`.
4. Returns `EnrollResponse(secret, otpAuthUri)`.

The base32 secret is returned **once** here and never again — the client must show/store it (typically as a QR of the `otpAuthUri`) immediately. MFA is **not yet active**: the user must confirm via `/verify`.

#### `POST /verify → ResponseEntity<Void> verify(@Valid CodeRequest body)`
Confirms enrollment. `CodeRequest.code` is `@NotBlank` (bean-validation rejects empty before the method runs).
- `confirmEnrollment(me.userId(), code)`:
  - **Failure path:** audit `auth.mfa.verify.failed` with `AuditOutcome.FAILED`, then throw `400 "Invalid code"`.
  - **Success path:** audit `auth.mfa.enabled`, return `204 No Content`.

Despite the path name `verify`, this endpoint is specifically **enrollment confirmation** (it flips `enabled=true`). The per-login code check is the separate `/mfa/login` endpoint in `AuthController`.

#### `POST /disable → ResponseEntity<Void> disable()`
- `mfaService.disable(me.userId())` (delete the row), audit `auth.mfa.disabled`, return `204`. Idempotent — disabling when not enrolled still returns `204`.

### Nested records
- `public record EnrollResponse(String secret, String otpAuthUri)` — JSON body for `/enroll`.
- `public record CodeRequest(@NotBlank String code)` — JSON body for `/verify`.

### Collaborators / call graph
- **Calls:** `MfaService` (all logic), `SecurityUtils.requireUser()` (identity), `AuditContext` / `AuditOutcome` (audit trail).
- **Called by:** the admin-ui SPA (MFA settings screen) and any API client managing their own second factor.

### Gotchas for a new engineer
- **Audit on enroll/disable is set unconditionally and is structured** (action + target). The `verify` endpoint records *both* a failure outcome (with `AuditOutcome.FAILED`) and the eventual `enabled` success — so the audit log distinguishes a fat-fingered code from a real activation.
- **Don't add a `userId` parameter** to any of these endpoints. The identity is always the session principal. Accepting a target user id would let one user enroll/disable another's MFA.
- The endpoint that actually *gates login with a code* is **not in this controller** — if you're tracing the second-factor check during sign-in, go to `AuthController#mfaLogin` (`POST /api/v1/auth/mfa/login`), which calls `MfaService.parseChallenge` + `verifyLoginCode`.
