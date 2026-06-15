# `com.example.cp.keys` — Signing-key lifecycle, KEK envelope encryption, and JWKS publication

> **Module overview.** This package is the cryptographic root of trust for the entire control panel. It owns the Ed25519 signing key pair(s) that sign every license `.lic` token and every CRL, the *key-encryption-key* (KEK) that protects all secret material at rest under AES-GCM, and the public `/.well-known/jwks.json` endpoint that lets offline verifiers trust those signatures. Concretely it does four things: (1) **manages the signing-key lifecycle** — generate, rotate, retire, mark-compromised — under a strict *single-ACTIVE-key* invariant; (2) **encrypts/decrypts private key material and all other tenant secrets** through a *versioned* KEK envelope (`KeyEncryptor`); (3) **rotates the KEK** across every encrypted column in the database in one transaction, with a startup *drop-guard* that refuses to boot if any blob would become permanently undecryptable; and (4) **publishes** the public half of the trusted keys as a JWKS document and offers an admin REST surface for rotation/compromise/listing. The package is deliberately the *only* place that knows how to turn a `SigningKey` row into usable key material, so the rest of the codebase (license issuance, CRL signing) depends on it rather than touching keys directly.

---

## Files at a glance

| File | Type | Responsibility |
|------|------|----------------|
| `SigningKey.java` | JPA `@Entity` | The persisted signing-key row (kid, PEM public key, encrypted private key, status, timestamps). |
| `SigningKeyRepository.java` | Spring Data repo | Lookups by kid/status and the "publishable for JWKS" query. |
| `KeyEncryptor.java` | `@Component` | AES-GCM envelope encryption with a **versioned** KEK; encrypt/decrypt/inspect blobs. |
| `KeyService.java` | `@Service` | The brain: key generation/rotation/compromise, KEK rotation across all encrypted columns, drop-guard, public-key/JWK conversion, active-key loading. |
| `JwsSigner.java` | `@Component` | Reusable Ed25519 JWS signer used by license issuance and CRL signing. |
| `JwksController.java` | `@RestController` | Serves `/.well-known/jwks.json`. |
| `KeyController.java` | `@RestController` | Admin REST surface: rotate, rotate-kek, compromise, list. |

A non-package collaborator the docs reference repeatedly: the Liquibase migration `control-panel-api/src/main/resources/db/changelog/changes/18-keys-active-signing-index.sql`, which creates the partial unique index `ux_signing_keys_single_active` that backs the single-ACTIVE invariant at the database layer.

---

## The big picture: how a license becomes verifiable

```
  admin POST /api/v1/admin/keys/rotate
        │
        ▼
  KeyController.rotate ──► KeyService.rotate ──► generateNewActiveKey  (@Transactional)
                                                   │  retire old ACTIVE rows, FLUSH
                                                   │  generate Ed25519 pair
                                                   │  encrypt private key  ──► KeyEncryptor.encrypt
                                                   │  INSERT new ACTIVE row (guarded by ux_signing_keys_single_active)
                                                   ▼
                                                outbox "KeyRotated"

  customer/license flow:
  LicenseIssuer ──► KeyService.getActiveSigningKeyPair() ──► KeyEncryptor.decrypt(private)  ──► ActiveKey
                ──► JwsSigner.sign(claims, "license+jwt", active) ──► compact EdDSA JWS (.lic)

  customer Docker app verifies OFFLINE:
  GET /.well-known/jwks.json ──► JwksController ──► KeyService.listPublishedKeys + toJwk
        (ACTIVE + recently-RETIRED public keys; COMPROMISED excluded)
```

The crucial property is **asymmetry of trust**: the panel holds the private key (encrypted at rest under the KEK); the customer app only ever sees public keys via the JWKS. Rotation and compromise propagate to verifiers purely by changing which public keys appear in the JWKS at their next refresh — there is no online callback to revoke a key.

---

## `SigningKey.java` — the persisted key row

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/SigningKey.java`
**Responsibility:** JPA entity mapping the `signing_keys` table. One row == one Ed25519 key pair in some lifecycle state.

### `public class SigningKey`
Lombok-generated `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`. Maps to `@Table(name = "signing_keys")`.

| Field | Column | Notes |
|-------|--------|-------|
| `UUID id` | `id` (PK, not null) | Surrogate primary key. Assigned in app code via `Ids.newId()` (a UUIDv7 — see the kid gotcha below), **not** DB-generated. |
| `String kid` | `kid` (unique, not null, len 64) | The JWS `kid` header value — the public, human-readable key identifier verifiers match against. Unique constraint enforced in DB. |
| `String algorithm` | `algorithm` (not null, len 32) | Always `"Ed25519"` in practice (the only algorithm `KeyService` generates). |
| `String publicKeyPem` | `public_key_pem` (`text`, not null) | The X.509 SubjectPublicKeyInfo public key, PEM-armored. **Stored in cleartext** — it is public by design. |
| `byte[] privateKeyEncrypted` | `private_key_encrypted` (not null) | The PKCS#8-encoded private key, wrapped in a `KeyEncryptor` AES-GCM envelope. Never stored in cleartext. |
| `Status status` | `status` (`EnumType.STRING`, not null) | `ACTIVE`, `RETIRED`, or `COMPROMISED`. |
| `OffsetDateTime createdAt` | `created_at` (not null) | When the key was minted. |
| `OffsetDateTime retiredAt` | `retired_at` (nullable) | When it left ACTIVE; set on retire and on compromise. |

### `public enum Status { ACTIVE, RETIRED, COMPROMISED }`
The lifecycle state machine:

```
            generateNewActiveKey()
   (none) ─────────────────────────► ACTIVE
                                        │
            new key minted (rotate)     │  markCompromised()
   ACTIVE ──────────────────────────► RETIRED          ACTIVE/RETIRED ──► COMPROMISED
                                        │                                     │
       still published in JWKS while    │   dropped from JWKS immediately ────┘
       retiredAt > now - 18 months      ▼
                                   eventually aged out of JWKS
```

- **ACTIVE** — exactly one at any time (invariant; see below). Used for new signing. Published in JWKS.
- **RETIRED** — superseded by a newer ACTIVE key. Still published in JWKS while within the 18-month retention window so previously-issued tokens keep verifying.
- **COMPROMISED** — excluded from JWKS *immediately*; verifiers stop trusting it at their next refresh. A terminal state.

**Gotcha:** the entity has **no `@Version` column**, so JPA optimistic locking is *not* what guards concurrent rotation — the database partial unique index does (see invariant section). Don't add concurrency assumptions based on the entity alone.

---

## `SigningKeyRepository.java` — persistence queries

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/SigningKeyRepository.java`
**Responsibility:** Spring Data JPA repository over `SigningKey`. The only persistence gateway for keys.

### `public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID>`

| Method | Returns | Why it exists / used by |
|--------|---------|-------------------------|
| `findByKid(String kid)` | `Optional<SigningKey>` | Look up a single key by its public id — used by `markCompromised`, `getPublicKey`. |
| `findFirstByStatusOrderByCreatedAtDesc(Status)` | `Optional<SigningKey>` | "The current ACTIVE key" — newest by `createdAt`. Used by `bootstrap` (existence check) and `getActiveSigningKeyPair` (load for signing). The `OrderByCreatedAtDesc` is defensive: even if a transient double-ACTIVE slipped past the index, this picks the newest. |
| `findByStatus(Status)` | `List<SigningKey>` | All keys in a status — used in `generateNewActiveKey` to find ACTIVE keys to retire. |
| `findByStatusOrRetiredAtAfter(Status, OffsetDateTime)` | `List<SigningKey>` | Legacy derived query (superseded by `findPublishable`; retained for compatibility — see its Javadoc). |
| `findPublishable(active, retired, cutoff)` | `List<SigningKey>` | **The JWKS query.** JPQL: returns rows where `status = ACTIVE` **OR** (`status = RETIRED` **AND** `retiredAt > cutoff`). COMPROMISED is structurally impossible to return. |

```jpql
SELECT k FROM SigningKey k
WHERE (k.status = :active)
   OR (k.status = :retired AND k.retiredAt > :cutoff)
```

**Why `findPublishable` replaced the derived query:** the older `findByStatusOrRetiredAtAfter` made the COMPROMISED exclusion implicit (it relied on COMPROMISED rows not matching the status/retiredAt predicate). `findPublishable` pins the published statuses to exactly ACTIVE/RETIRED, so a COMPROMISED key can never leak into the JWKS even if some future code path sets `retiredAt` on it (which `markCompromised` in fact does).

---

## `KeyEncryptor.java` — versioned AES-GCM envelope encryption (encryption at rest)

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/KeyEncryptor.java`
**Responsibility:** Encrypt and decrypt arbitrary secret byte arrays under a *versioned* key-encryption-key. This is the single crypto primitive protecting **all** secrets at rest in the system — not just signing private keys, but also TOTP/MFA secrets, webhook HMAC secrets, and OIDC client secrets (see `KeyService.ENCRYPTED_COLUMNS`). It is a stateless-per-call AES-GCM helper plus a small in-memory registry of configured KEKs.

### Why "versioned"?
A single static master key can never be rotated without re-encrypting (or losing) every blob it ever protected. So every ciphertext this class produces *carries the id of the KEK that encrypted it*. Old rows keep decrypting under their old KEK while new rows use the active one, and `KeyService.rotateKek()` can walk every blob and migrate it to the active KEK. This is the design that makes KEK rotation possible at all.

### Configuration properties (constructor `@Value`s)

| Property | Field | Meaning |
|----------|-------|---------|
| `app.signing.master-key` (env `APP_KEY_ENC_MASTER`) | `legacyMasterKeyB64` | The legacy *single* master key. If set, registered under the reserved id `"default"`. Back-compat path. |
| `app.signing.master-keys` | `masterKeysSpec` | The versioned KEK list: `id:base64key` entries, comma- or whitespace-separated. |
| `app.signing.active-master-key-id` | `configuredActiveKekId` | Which KEK id is used for *new* encryptions. |

### Constants & layout
- `DEFAULT_KEK_ID = "default"` — reserved id for the legacy single key.
- `VERSIONED_MAGIC = 0x01` — first byte marking a versioned envelope.
- `IV_LEN = 12`, `TAG_BITS = 128` (`TAG_BYTES = 16`), `TRANSFORM = "AES/GCM/NoPadding"`, `MAX_ID_LEN = 255`.

**Blob layouts:**

```
Versioned (produced going forward):
  [0x01 magic][1-byte idLen][idLen bytes UTF-8 KEK id][IV 12 bytes][ciphertext || GCM tag(16)]

Legacy / unversioned (written before versioning existed):
  [IV 12 bytes][ciphertext || GCM tag(16)]      decrypted under the "default" KEK
```

### `@PostConstruct void init()`
Builds the in-memory `keks` map (`LinkedHashMap<String, SecretKeySpec>`, insertion-ordered for deterministic logging) and resolves `activeKekId`. Steps:
1. If `legacyMasterKeyB64` is non-blank, register it under `"default"`.
2. Parse `masterKeysSpec` entries `id:base64`. Validates: separator present, id non-blank, id ≤ 255 UTF-8 bytes; throws `IllegalStateException` on malformed entries.
3. If no KEK at all was configured → fail fast with a clear message (the app cannot decrypt anything).
4. Resolve the **active** KEK id:
   - explicit `active-master-key-id` (must exist in the map, else throw), else
   - `"default"` if present (back-compat), else
   - the first declared versioned KEK.
5. Log the configured KEK ids and which is active.

**Why fail-fast here:** a misconfigured master key is a fatal, silent-data-loss-class problem. Surfacing it at boot (rather than at first decrypt during, say, MFA login) is intentional.

### `private SecretKeySpec toKeySpec(String id, String b64)`
Base64-decodes the key. Accepts exactly **16/24/32** bytes as a raw AES key. **Otherwise derives** a 32-byte key via `SHA-256(raw)` so test/staging fixtures with arbitrary-length entropy still work without redeployment. Requires at least 16 bytes of decoded entropy. Throws `IllegalStateException` on bad base64 or insufficient length.

> **Gotcha / security note:** the SHA-256 derivation path means a 20-byte or 50-byte base64 key won't be rejected — it's silently hashed to 256 bits. In production you should supply a real 32-byte key; the derivation is a convenience for fixtures, not a KDF you should lean on (no salt, single round).

### `public String activeKekId()`
The KEK id used for new encryptions and the target of `rotateKek()`.

### `public byte[] encrypt(byte[] plaintext)` / `public byte[] encrypt(byte[] plaintext, String kekId)`
- The no-arg-kek overload encrypts under the active KEK.
- The explicit-kek overload is used by KEK rotation (though in practice rotation always targets the active KEK).
- Generates a fresh random 12-byte IV per call via `SecureRandom`, runs `AES/GCM/NoPadding`, then assembles the **versioned** envelope: `magic | idLen | idBytes | iv | ciphertext+tag`.
- Throws `IllegalStateException` for an unknown `kekId`; wraps any crypto failure in `RuntimeException`.

**Concurrency note:** the shared `SecureRandom rng` and `Cipher.getInstance` per call are fine — `Cipher` instances are created locally per invocation (not shared), and `SecureRandom.nextBytes` is thread-safe.

### `public byte[] decrypt(byte[] blob)`
The most subtle method in the package. Decryption strategy:
1. Reject blobs `<= IV_LEN` as too short.
2. Try to parse as **versioned** (`tryParseVersioned`).
3. If it parses *and* the embedded KEK id is configured → decrypt under that KEK. Done.
4. If it parses but the KEK id is **unknown**, two cases collapse here:
   - **(a)** a genuine versioned blob written under a now-removed KEK, or
   - **(b)** a *legacy* blob whose first byte happened to equal `0x01` and whose following bytes happened to form a self-consistent (but bogus) versioned header — roughly 1 in ~1400–2200 of pre-versioning blobs.

   To recover case (b), if a `"default"` KEK exists it **falls back to the legacy layout** and tries to decrypt. The GCM tag is the safety net: a wrong interpretation fails authentication rather than returning garbage. If the legacy attempt also fails, it re-surfaces a precise "unknown KEK id" `IllegalStateException` (case (a)) instead of an opaque GCM error.
5. If the blob doesn't parse as versioned at all → `decryptLegacy`.

> **Why this matters:** before this fallback existed, ~1/1400 legacy blobs were permanently and unexpectedly undecryptable after versioning shipped. The fallback makes the transition lossless. Authentication via the GCM tag is what makes it *safe* to "guess" the interpretation.

### `public String referencedKekId(byte[] blob)`
Returns the KEK id a blob references **without decrypting it** — versioned blobs report their embedded id (even if that id is no longer configured), legacy blobs report `"default"`. This is exactly what the drop-guard needs: it must know *which* KEK a blob depends on, not whether it can be read right now.

### `public Set<String> configuredKekIds()`
Unmodifiable view of every KEK id currently able to decrypt. Used by the drop-guard to compute orphans.

### Private helpers
- `decryptLegacy(byte[])` — splits `[IV][ct||tag]` and decrypts under `"default"`; throws if no default KEK is configured.
- `decryptWith(key, iv, ct, desc)` — the actual `AES/GCM/NoPadding` decrypt; wraps failures with a human-readable `desc`.
- `tryParseVersioned(byte[])` — returns a `Versioned` record or `null`. A blob is "self-consistent versioned" only if it starts with the magic byte, declares a non-zero id length, leaves room for at least `IV + TAG` after the header, and the id is valid UTF-8. Returning `null` (not throwing) is deliberate so callers can cleanly fall back to legacy.
- `private record Versioned(String kekId, byte[] iv, byte[] ct)` — parse result.

**Collaborators:** called by `KeyService` (encrypt on generate, decrypt on load/rotate, `referencedKekId`/`configuredKekIds` in the drop-guard). It has no outbound dependencies beyond the JCA and Spring config.

---

## `KeyService.java` — lifecycle, rotation, KEK migration, JWK conversion

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/KeyService.java`
**Responsibility:** The orchestration brain of the package. Generates and rotates signing keys; marks them compromised; rotates the KEK across *all* encrypted columns; runs the startup drop-guard; loads the active key for signing; and converts public keys into PEM/JWK/raw-byte forms.

### Constants
- `ALGORITHM = "Ed25519"` — the only signing algorithm.
- `RETENTION_MONTHS = 18` — RETIRED keys remain in the JWKS for 18 months after retirement.

### `static final List<EncryptedColumn> ENCRYPTED_COLUMNS`
The **single source of truth** for every `(table, pkColumn, blobColumn, nullable)` that stores a `KeyEncryptor` blob:

| Table | PK column | Blob column | Nullable |
|-------|-----------|-------------|----------|
| `signing_keys` | `id` | `private_key_encrypted` | no |
| `user_mfa` | `user_id` | `secret_enc` | no |
| `webhook_subscriptions` | `id` | `secret_enc` | no |
| `sso_providers` | `id` | `client_secret_enc` | yes |

One KEK protects all four categories. This registry keeps KEK rotation and the drop-guard *in lock-step*: dropping a KEK after rotating only `signing_keys` would permanently orphan MFA/webhook/SSO secrets. The non-`keys` columns are touched **generically via native SQL**, so `KeyService` has *no compile-time dependency* on the mfa/webhooks/sso packages.

#### `record EncryptedColumn(String table, String pkColumn, String blobColumn, boolean nullable)`
Description of one encrypted column. The `nullable` flag reflects that `sso_providers.client_secret_enc` may be null (public OIDC clients).

> **Security gotcha:** the table/column names from this list are interpolated directly into SQL strings in `referencedKekIds` and `reEncryptColumn`. That is safe **only because the values are hard-coded constants**, never user input. Never make `ENCRYPTED_COLUMNS` dynamic/configurable without parameterizing/whitelisting — it would become a SQL-injection vector.

### Constructor & the self-proxy
```java
public KeyService(SigningKeyRepository repo, KeyEncryptor encryptor, OutboxPublisher outbox,
                  JdbcTemplate jdbc, @Lazy KeyService self)
```
- `repo` — JPA access; `encryptor` — the KEK primitive; `outbox` — transactional event publishing (`OutboxPublisher` from the subscriptions package); `jdbc` — `JdbcTemplate` for the generic native-SQL column scans/updates.
- `@Lazy KeyService self` — **a proxy reference to itself**. This is the key to making `@Transactional` work from the `@EventListener`. A direct `this.generateNewActiveKey()` call would be a self-invocation that bypasses Spring's transactional AOP proxy; calling `self.generateNewActiveKey()` re-enters through the proxy so the method actually runs in its own transaction. `@Lazy` breaks the constructor circular dependency.

### `@EventListener(ApplicationReadyEvent.class) @Order(0) public void bootstrap()`
On startup, if no ACTIVE signing key exists, generate the initial one (via the self-proxy, so it gets a transaction). Catches:
- `DataIntegrityViolationException` → another instance won the bootstrap race and inserted the single ACTIVE key first; the partial unique index rejected ours. **Treated as success** (exactly one ACTIVE key is the goal).
- any other `Exception` → log and defer (don't crash the app on a transient startup hiccup).

`@Order(0)` ensures it runs *before* the drop-guard (`@Order(100)`), so the freshly-minted bootstrap key is included in the guard's scan.

### `@EventListener(ApplicationReadyEvent.class) @Order(100) public void assertNoOrphanedKekReferences()` — the KEK **drop-guard**
Refuses to start if any stored blob references a KEK id that is no longer configured (which would make that blob permanently undecryptable). Flow:

```
for each EncryptedColumn col in ENCRYPTED_COLUMNS:
    referenced = referencedKekIds(col)          # distinct KEK ids in that column's blobs
    for kekId in referenced:
        if kekId not in encryptor.configuredKekIds():
            orphans += "col.table.col.blobColumn -> KEK 'kekId'"
if orphans not empty:
    throw IllegalStateException(...)             # fail fast at boot
```

The thrown message tells the operator exactly what to do: re-add the missing KEK(s) and run `POST /api/v1/admin/keys/rotate-kek` *before* removing them. This converts a latent "secret silently undecryptable at MFA login / webhook delivery / SSO" failure into a loud boot-time refusal.

### `private Set<String> referencedKekIds(EncryptedColumn col)`
Native `SELECT <blobColumn> FROM <table> WHERE <blobColumn> IS NOT NULL`, then `encryptor.referencedKekId(blob)` for each. Skips (with a warning) blobs that are too short/garbage — that's a separate data-corruption concern, not a dropped-KEK concern, and it would surface loudly at actual decrypt time anyway. Returns a `LinkedHashSet` of distinct ids.

### `@Transactional public SigningKey generateNewActiveKey()` — the core mint+rotate
The heart of the lifecycle. Steps:
1. Generate an Ed25519 `KeyPair` via JCA.
2. Compute `kid` (`generateKid()`), PEM-encode the public key, and **encrypt the PKCS#8 private bytes** under the active KEK (`encryptor.encrypt`).
3. **Retire every existing ACTIVE key, then `repo.flush()`** *before* inserting the new one.
4. Insert the new ACTIVE row (`id = Ids.newId()`, status ACTIVE, `createdAt = now`).
5. Set `AuditContext` (`key.rotated`, target `signing_key:kid`).
6. Publish a `KeyRotated` outbox event (best-effort: failures are logged, not fatal).
7. Return the saved key.

> **The flush is load-bearing (concurrency/ordering):** Hibernate's default flush *action order* executes INSERTs before UPDATEs within a single flush. Without the explicit `repo.flush()` after the retire UPDATEs, the new ACTIVE INSERT would hit the DB while the old row is still ACTIVE — instantly violating `ux_signing_keys_single_active`. Flushing the retires first guarantees at most one ACTIVE row exists mid-transaction.

### `@Transactional public SigningKey rotate()`
Thin alias for `generateNewActiveKey()`. This is what `KeyController.rotate` (and `KeyController.rotate-kek` does *not*) calls.

### `@Transactional public Optional<SigningKey> markCompromised(String kid)`
Flags a key COMPROMISED so it drops from the JWKS immediately. Behavior:
- Look up by kid; 404 (`ApiException.notFound`) if missing.
- **Idempotent:** if already COMPROMISED, set audit context and return `Optional.empty()` *without* generating a replacement (never re-mint on a repeat call).
- Otherwise set status COMPROMISED, set `retiredAt` if not already set, save.
- Audit (`key.compromised`) + best-effort `KeyCompromised` outbox event (carries `was_active`).
- **If the compromised key had been ACTIVE**, generate a fresh ACTIVE key (so signing/issuance continues without a gap) and return it. `generateNewActiveKey` only retires keys *still in ACTIVE status*, so the just-saved COMPROMISED row is left untouched.

Returns the replacement ACTIVE key when one was generated; otherwise empty.

> **Why generate a replacement only when the compromised key was ACTIVE:** compromising a long-retired key doesn't affect current signing, so no new key is needed — and minting one anyway would needlessly churn the ACTIVE key.

### `@Transactional public int rotateKek()` — KEK migration across all columns
Re-encrypts **every** `KeyEncryptor`-protected secret under the currently-active KEK, across all four `ENCRYPTED_COLUMNS`. For each column it calls `reEncryptColumn`. After one call, every blob is tagged with the active KEK id, so the old KEK can be safely removed. Then sets audit (`key.kek.rotated`, target `kek:activeKekId`) and publishes a `KekRotated` outbox event with per-column counts. Returns the total rows re-encrypted.

> **Atomicity:** the whole operation is one `@Transactional` unit — a failure rolls *every* column back, so you never end up half-rotated. Rows already under the active KEK are still re-wrapped (idempotent and cheap), which keeps the logic uniform.

### `private int reEncryptColumn(EncryptedColumn col)`
Generic native-SQL re-wrap of one column:
- `SELECT <pk>, <blob> FROM <table> WHERE <blob> IS NOT NULL`.
- For each non-empty blob: `encryptor.encrypt(encryptor.decrypt(blob))` (decrypt transparently handles legacy/old-KEK blobs; encrypt tags with the active KEK).
- Targeted single-row `UPDATE <table> SET <blob>=? WHERE <pk>=?`.
- Returns the count re-encrypted.

> Using a per-row primary-key-targeted UPDATE (rather than a set-based statement) is what makes this work generically across heterogeneous tables — the only column types it needs to know are the PK and the blob.

### `@Transactional(readOnly = true) public ActiveKey getActiveSigningKeyPair()`
Loads the current ACTIVE key for signing: finds the newest ACTIVE row (404 if none), **decrypts** the private bytes, reconstructs a `PrivateKey` from PKCS#8 and a `PublicKey` from the stored PEM, and returns an `ActiveKey(kid, priv, pub)`. This is the single entry point that turns persisted/encrypted material into usable signing keys.

**Collaborators (callers):** `JwsSigner.sign(claims, typ)` and `LicenseIssuer` (which fetches an `ActiveKey` and passes it to `JwsSigner`).

### `@Transactional(readOnly = true) public Optional<PublicKey> getPublicKey(String kid)`
Parses the stored PEM for a given kid into a `PublicKey`. Used where a verifier-side public key is needed by kid.

### `@Transactional(readOnly = true) public List<SigningKey> listAll()`
All rows, unfiltered. Backs `listForAdmin`.

### `@Transactional(readOnly = true) public List<SigningKey> listPublishedKeys()`
The JWKS feed: ACTIVE + RETIRED-within-`RETENTION_MONTHS`, COMPROMISED excluded. Computes `cutoff = now - 18 months` and delegates to `repo.findPublishable`.

### `public JWK toJwk(SigningKey row)`
Builds a Nimbus `OctetKeyPair` (OKP, curve Ed25519, EdDSA, `use=sig`, `kid` set) from a key row's public PEM. **Defensive backstop:** throws `IllegalStateException` if handed a COMPROMISED row — the JWKS path already filters those via `listPublishedKeys`, but this guards a direct caller. Wraps failures in `RuntimeException`.

### `static PublicKey parsePublicKeyPem(String pem)` / `static String toPublicKeyPem(PublicKey pub)`
Symmetric PEM <-> `PublicKey` helpers. `toPublicKeyPem` armors the DER as 64-char lines between `BEGIN/END PUBLIC KEY`. `parsePublicKeyPem` strips the armor/whitespace, base64-decodes, and reconstructs via `X509EncodedKeySpec`.

### `public static byte[] extractRawEd25519PublicBytes(PublicKey pub)`
Extracts the **32-byte raw** Ed25519 public key from a JCA `PublicKey`. Ed25519 X.509 SPKI is exactly 44 bytes (12-byte algorithm header + 32-byte key), so the fast path strips the 12-byte prefix. A defensive fallback handles oddly-encoded providers via the `EdECPublicKey` interface: it reconstructs the 32-byte little-endian Y coordinate and applies the x-coordinate sign bit per RFC 8032 §5.1.2. Throws if neither path applies. **Reused by `JwsSigner` and `toJwk`** — extracted to avoid duplicating this fiddly encoding logic.

### `private static String generateKid()`
Produces `key-<yyyyMMdd-HHmmss>-<12 hex>`. The 12 hex chars are the **last** 12 of a fresh UUID, deliberately *not* the first 8.

> **Subtle bug-class avoided:** `Ids.newId()` is a time-ordered UUIDv7, whose *first* hex chars are a timestamp prefix. Two keys minted within the same instant (e.g. a compromise replacement minted the same second as the bootstrap key) would collide on those prefix bytes, violating the unique `kid` constraint. Using the *random* tail segment avoids the collision.

### Records returned to callers
- `public record ActiveKey(String kid, PrivateKey privateKey, PublicKey publicKey)` — the in-memory decrypted active key handed to `JwsSigner`.
- `public record SigningKeyView(UUID id, String kid, String algorithm, String status, OffsetDateTime createdAt, OffsetDateTime retiredAt, String publicKeyPem)` — the admin/API DTO. **Note it exposes only the public PEM, never the encrypted private blob.**
- `@Transactional(readOnly = true) public List<SigningKeyView> listForAdmin()` — maps `listAll()` to `SigningKeyView`s for the admin UI.

---

## `JwsSigner.java` — reusable Ed25519 JWS signing

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/JwsSigner.java`
**Responsibility:** Sign a `JWTClaimsSet` into a compact EdDSA JWS with the active key's `kid` in the header. Extracted from `LicenseIssuer.signJwt` so both **license issuance** and **CRL signing** share one implementation of the OctetKeyPair / raw-byte assembly.

### `@Component public class JwsSigner`
Constructor-injects `KeyService`.

### `public String sign(JWTClaimsSet claims, String typ, KeyService.ActiveKey active)`
The primary overload. Builds an `OctetKeyPair` from the active key's raw public *and private* bytes (private via `d(...)`), constructs a `JWSHeader` (`alg=EdDSA`, `kid=active.kid()`, `typ=<typ>`), signs with `Ed25519Signer`, and returns the serialized compact JWS. The `typ` parameter distinguishes token kinds — observed values: `"license+jwt"` (license issuance) and `"crl+jwt"` (CRL signing). Wraps failures in `RuntimeException("Failed to sign " + typ, ...)`.

### `public String sign(JWTClaimsSet claims, String typ)`
Convenience overload that resolves the active key itself via `keyService.getActiveSigningKeyPair()` — used by callers (e.g. `CrlController`) that don't already hold an `ActiveKey`.

### `static byte[] extractRawEd25519PrivateBytes(PrivateKey priv)`
Extracts the **32-byte raw** Ed25519 private scalar from a PKCS#8-encoded key. Java's SunEC PKCS#8 layout wraps the 32-byte CurvePrivateKey in an inner `OCTET STRING`, so it scans for the `0x04 0x20` marker (OCTET STRING tag + length 32) and takes the next 32 bytes. Fallback: if the key implements `EdECPrivateKey`, use `getBytes()`. Throws if neither works.

> **Gotcha:** the loop bound is `i + 33 < enc.length`, and it matches the first `0x04 0x20` it finds. For the standard 48-byte SunEC layout the marker sits at a fixed offset, so this is reliable in practice; but it is a heuristic scan, not a full ASN.1 parse. Don't reuse it for arbitrary key encodings.

**Collaborators:** calls `KeyService.extractRawEd25519PublicBytes` and `KeyService.getActiveSigningKeyPair`. Called by `LicenseIssuer` (license `.lic` tokens) and `CrlController` (signed CRLs). This is why the `keys` package is a dependency of the licenses package, not vice versa.

---

## `JwksController.java` — public JWKS endpoint

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/JwksController.java`
**Responsibility:** Serve the public JSON Web Key Set at `GET /.well-known/jwks.json`. This is the **only** way offline customer verifiers learn which public keys to trust.

### `@RestController public class JwksController`
Constructor-injects `KeyService`.

### `@GetMapping("/.well-known/jwks.json") public ResponseEntity<Map<String,Object>> jwks()`
Calls `keyService.listPublishedKeys()` (ACTIVE + recently-RETIRED, never COMPROMISED), converts each row to a JWK via `keyService.toJwk(row).toJSONObject()`, wraps them in `{ "keys": [...] }`, and returns `200` with `Cache-Control: public, max-age=300`.

> **Security/operational notes:**
> - This endpoint is **unauthenticated by design** — it serves only public key material; verifiers fetch it without credentials.
> - The 5-minute (`max-age=300`) cache is the *propagation latency* for rotation/compromise: when you compromise a key, downstream verifiers may keep trusting it until their cache expires and they refetch. This is the inherent trade-off of *offline* verification — there is no instantaneous online revocation; the CRL (signed via `JwsSigner`) covers per-license revocation between JWKS refreshes.

---

## `KeyController.java` — admin REST surface

**Path:** `control-panel-api/src/main/java/com/example/cp/keys/KeyController.java`
**Responsibility:** Authenticated admin endpoints under `/api/v1/admin/keys` for rotating signing keys, rotating the KEK, compromising a key, and listing keys.

### `@RestController @RequestMapping("/api/v1/admin/keys") public class KeyController`
Constructor-injects `KeyService`.

| Endpoint | Method | Authority | Delegates to | Returns |
|----------|--------|-----------|--------------|---------|
| `POST /rotate` | `rotate()` | `key.rotate` | `keyService.rotate()` | `SigningKeyView` of the new ACTIVE key |
| `POST /rotate-kek` | `rotateKek()` | `key.rotate` | `keyService.rotateKek()` | `KekRotationResult(reEncrypted)` |
| `POST /{kid}/compromise` | `markCompromised(kid)` | `key.rotate` | `keyService.markCompromised(kid)` | `CompromiseResult(kid, replacementGenerated, replacement)` |
| `GET ` (root) | `list()` | `key.rotate` **or** `key.read` | `keyService.listForAdmin()` | `List<SigningKeyView>` |

All write endpoints require the `key.rotate` authority (`@PreAuthorize`); listing accepts `key.rotate` or the read-only `key.read`. This split lets you grant operators visibility without the power to rotate/compromise.

> **Important distinction for operators — two very different "rotate"s:**
> - `POST /rotate` rotates the **signing key**: retires the current ACTIVE key and mints a new ACTIVE one. This *does* change what the JWKS publishes and which key signs new licenses.
> - `POST /rotate-kek` rotates the **key-encryption-key**: re-encrypts existing private material (and all other secrets) under the active KEK. It does **not** change which signing key is ACTIVE or what the JWKS shows. It's the migration step you run after adding a new KEK and before removing the old one (see the drop-guard).

### Helpers & response records
- `private static SigningKeyView view(SigningKey k)` — maps an entity to the public DTO (no private material).
- `public record KekRotationResult(int reEncrypted)` — how many blob rows were re-encrypted.
- `public record CompromiseResult(String kid, boolean replacementGenerated, SigningKeyView replacement)` — `replacementGenerated` is true (and `replacement` non-null) only when the compromised key had been ACTIVE.

---

## The single-ACTIVE-key invariant (cross-cutting)

There must be **at most one ACTIVE signing key** at any time. This is enforced at **two layers**, defense-in-depth:

1. **Application logic** (`generateNewActiveKey`): retire all ACTIVE keys, `flush()`, then insert the new ACTIVE key — guaranteeing at most one ACTIVE row mid-transaction within a single process.
2. **Database** (`ux_signing_keys_single_active`, migration `18-keys-active-signing-index.sql`): a *partial* unique index on `signing_keys(status) WHERE status = 'ACTIVE'`. Under READ COMMITTED, two concurrent rotate/bootstrap transactions could each pass the retire step and both try to insert an ACTIVE row; the index makes the second committer fail with a unique-constraint violation. RETIRED/COMPROMISED rows (of which there may be many) are unaffected by the predicate.

The application *cooperates* with the index rather than fighting it: `bootstrap` catches the resulting `DataIntegrityViolationException` and treats "another instance won the race" as success, because the post-condition (exactly one ACTIVE key) holds. There is no `@Version`/optimistic lock on the entity — the partial index *is* the concurrency control.

---

## KEK rotation runbook (how the pieces interlock)

```
1. Add the NEW kek to app.signing.master-keys (keep the OLD one configured)
   set app.signing.active-master-key-id = NEW
2. Restart  ──► drop-guard passes (every existing blob still references a CONFIGURED kek: the OLD one)
3. POST /api/v1/admin/keys/rotate-kek
   ──► rotateKek() re-encrypts EVERY blob in ENCRYPTED_COLUMNS under NEW, in one transaction
4. Now every blob references NEW.  Remove the OLD kek from config.
5. Restart  ──► drop-guard passes (nothing references OLD anymore)

   ⚠ If you skip step 3 and remove OLD at step 4, the drop-guard at step 5 REFUSES to start
     and tells you exactly which columns still pin the OLD kek.
```

The four invariants this package upholds, summarized:
- **Exactly one ACTIVE signing key** (app logic + partial unique index).
- **Private material never at rest in cleartext** (`KeyEncryptor` AES-GCM envelope).
- **No blob ever becomes undecryptable** (versioned envelopes + drop-guard + atomic `rotateKek`).
- **COMPROMISED keys disappear from the JWKS immediately** (`findPublishable`/`toJwk` exclusion), bounded only by the 5-minute JWKS cache.

---

## How it fits the bigger picture

The `keys` package is the trust anchor everything downstream stands on. **License issuance** (`com.example.cp.licenses.LicenseIssuer`) asks `KeyService` for the active key and uses `JwsSigner` to mint signed `.lic` tokens; **CRL signing** (`com.example.cp.licenses.CrlController`) uses `JwsSigner` to sign revocation lists. **Customer Docker apps** never call any of these admin/service methods — they only fetch `/.well-known/jwks.json` (this package's `JwksController`) and verify tokens *offline* with the bundled verifier SDK + Spring Boot starter, which is precisely why key rotation/compromise propagates via JWKS-with-cache rather than online revocation. Meanwhile the `KeyEncryptor` + `ENCRYPTED_COLUMNS` + drop-guard machinery quietly protects secrets owned by *other* packages too — MFA (`user_mfa`), webhooks (`webhook_subscriptions`), and SSO (`sso_providers`) — making this package the system-wide encryption-at-rest authority, not merely the signing-key store. Audit (`AuditContext`) and the transactional outbox (`OutboxPublisher`) tie key lifecycle events into the platform's compliance and event-streaming story.
