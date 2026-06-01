# Design spec: license-revocation

## Shared contracts (other files depend on these — keep signatures exact)

### `SignedCrl (JWS schema)` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/licenses/CrlController.java`
- **Purpose:** The signed CRL artifact returned by GET /api/v1/licenses/crl. It is a compact JWS (three dot-separated base64url parts) — NOT a JSON object. Header: {alg:"EdDSA", kid:"<active kid>", typ:"crl+jwt"}. Claims body: {"iss":"<app.signing.issuer>", "iat":<epoch-seconds>, "nextUpdate":<epoch-seconds>, "revoked":["lic_...", ...]}. Signed with the active Ed25519 key so it verifies against the existing /.well-known/jwks.json. nextUpdate = iat + app.signing.crl-ttl. The `revoked` array holds the full set of REVOKED jti (not a delta). Served with Content-Type application/jwt and Cache-Control public,max-age=<crl-ttl-seconds>.
- **Signature/contract:**

```
GET /api/v1/licenses/crl -> 200 text/plain (compact JWS string); claims {iss,iat,nextUpdate,revoked:[String]}
```

### `JwsSigner (server)` (class)
- **File:** `control-panel-api/src/main/java/com/example/cp/keys/JwsSigner.java`
- **Purpose:** Reusable Ed25519 JWS signer extracted from LicenseIssuer.signJwt so both LicenseIssuer and CrlController sign with the active key without duplicating OctetKeyPair/raw-byte extraction logic.
- **Signature/contract:**

```
@Component class JwsSigner { String sign(JWTClaimsSet claims, String typ, KeyService.ActiveKey active); }
```

### `RevocationChecker (verifier lib)` (interface)
- **File:** `license-verifier/src/main/java/com/example/licenseverifier/RevocationChecker.java`
- **Purpose:** Abstraction the LicenseVerifier consults to decide whether a verified license jti is revoked. Default no-op impl means existing callers/tests keep passing; the starter supplies a CRL-backed impl that fails closed when stale.
- **Signature/contract:**

```
public interface RevocationChecker { boolean isRevoked(String jti); default boolean isOperational(){return true;} static RevocationChecker none(){...} }
```

### `RevocationList (verifier lib model)` (class)
- **File:** `license-verifier/src/main/java/com/example/licenseverifier/RevocationList.java`
- **Purpose:** Immutable parsed representation of a signed CRL: issuer, issuedAt, nextUpdate, and the revoked jti set. Returned by CrlVerifier.parse() and held by the starter cache.
- **Signature/contract:**

```
public final class RevocationList { String issuer(); Instant issuedAt(); Instant nextUpdate(); Set<String> revokedJtis(); boolean isRevoked(String jti); boolean isStale(Instant now, Duration skew); }
```

### `CrlVerifier (verifier lib)` (class)
- **File:** `license-verifier/src/main/java/com/example/licenseverifier/CrlVerifier.java`
- **Purpose:** Parses + cryptographically verifies a signed CRL JWS against a PublicKeyProvider (the same JWKS used for licenses), enforcing typ=crl+jwt, EdDSA, known kid, and issuer match. Mirrors LicenseVerifier's resolveVerifier logic.
- **Signature/contract:**

```
public final class CrlVerifier { CrlVerifier(PublicKeyProvider keys, String expectedIssuer); RevocationList verify(String crlJws); }
```

### `LicenseRevokedException (verifier lib)` (class)
- **File:** `license-verifier/src/main/java/com/example/licenseverifier/exceptions/LicenseRevokedException.java`
- **Purpose:** Thrown by LicenseVerifier when a license's jti is in the revocation set. Extends LicenseException so existing catch(LicenseException) handlers still catch it.
- **Signature/contract:**

```
public class LicenseRevokedException extends LicenseException { LicenseRevokedException(String jti); String getJti(); }
```

### `CrlRevocationChecker (starter)` (class)
- **File:** `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/CrlRevocationChecker.java`
- **Purpose:** Scheduled CRL fetch+verify; caches the RevocationList; implements RevocationChecker. Fails CLOSED: when the cached CRL is stale (now > nextUpdate + skew) or never loaded, isOperational() returns false and isRevoked() returns true for every jti so all guarded calls are denied.
- **Signature/contract:**

```
public class CrlRevocationChecker implements RevocationChecker { void refresh(); @Scheduled fetch; boolean isRevoked(String jti); boolean isOperational(); }
```

### `app.signing.crl-ttl` (config-property)
- **File:** `control-panel-api/src/main/resources/application.yml`
- **Purpose:** Server-side: how long a signed CRL is valid; drives the nextUpdate claim and Cache-Control max-age. Default PT1H.
- **Signature/contract:**

```
app.signing.crl-ttl: ${APP_CRL_TTL:PT1H} (Duration)
```

### `app.license.crl-url` (config-property)
- **File:** `license-verifier-spring-boot-starter (LicenseProperties)`
- **Purpose:** Starter: absolute URL of the signed CRL endpoint. If blank, revocation checking is disabled (RevocationChecker.none()).
- **Signature/contract:**

```
app.license.crl-url: String (e.g. https://control-panel/api/v1/licenses/crl)
```

### `app.license.crl-refresh-interval` (config-property)
- **File:** `license-verifier-spring-boot-starter (LicenseProperties)`
- **Purpose:** Starter: how often to re-fetch the signed CRL. Default PT15M.
- **Signature/contract:**

```
app.license.crl-refresh-interval: Duration = PT15M
```

### `app.license.crl-max-stale` (config-property)
- **File:** `license-verifier-spring-boot-starter (LicenseProperties)`
- **Purpose:** Starter: grace period past nextUpdate before the cached CRL is treated as stale and the checker fails closed. Default PT1H.
- **Signature/contract:**

```
app.license.crl-max-stale: Duration = PT1H
```

## File edits

### [NEW FILE] `control-panel-api/src/main/java/com/example/cp/keys/JwsSigner.java`
- depends on: JwsSigner (server)
- NEW @Component in package com.example.cp.keys.
- Move the body of LicenseIssuer.signJwt + extractRawEd25519PrivateBytes here so both license issuance and CRL signing share one signer. Public method: `public String sign(com.nimbusds.jwt.JWTClaimsSet claims, String typ, KeyService.ActiveKey active)`.
- Implementation copied verbatim from LicenseIssuer.signJwt lines 97-119: build OctetKeyPair from KeyService.extractRawEd25519PublicBytes(active.publicKey()) and the raw private bytes; build JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(active.kid()).type(new JOSEObjectType(typ)); SignedJWT.sign(new Ed25519Signer(okp)); return serialize(). Wrap exceptions in RuntimeException("Failed to sign "+typ, e).
- Make extractRawEd25519PrivateBytes a `static` method on JwsSigner (identical to the one in LicenseIssuer lines 126-142) so it is reusable.
- Add a convenience overload `public String sign(JWTClaimsSet claims, String typ)` that calls keyService.getActiveSigningKeyPair() internally — used by CrlController. Inject KeyService via constructor.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/licenses/LicenseIssuer.java`
- depends on: JwsSigner (server)
- Add `private final com.example.cp.keys.JwsSigner jwsSigner;` field; add it to the constructor params and assignment.
- Replace the call `String jwt = signJwt(built, active);` (line 60) with `String jwt = jwsSigner.sign(built.claims(), "license+jwt", active);`.
- Delete the private signJwt method (lines 97-119) and the now-unused static extractRawEd25519PrivateBytes (lines 126-142) and their now-unused imports (JOSEObjectType, JWSAlgorithm, JWSHeader, Ed25519Signer, Curve, OctetKeyPair, Base64URL, SignedJWT, EdECPrivateKey). KEEP MessageDigest/HexFormat imports used by sha256TruncatedHex. NOTE: keep behavior identical — typ stays "license+jwt".
- (If minimizing churn is preferred, leave signJwt in place and only add CrlController’s own signer — but extraction is the cleaner P0; either way CrlController must reuse KeyService, do not re-implement key loading.)

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/licenses/CrlController.java`
- depends on: SignedCrl (JWS schema), JwsSigner (server)
- Add a new endpoint `@GetMapping(value="/crl", produces="application/jwt")` `@PreAuthorize("permitAll()")` returning `ResponseEntity<String>` (the compact JWS). Keep the existing /revoked JSON endpoint as-is for backward compat (it is already permitAll and in SecurityConfig).
- Inject `JwsSigner jwsSigner` and `@Value("${app.signing.issuer}") String issuer` and `@Value("${app.signing.crl-ttl:PT1H}") java.time.Duration crlTtl` via constructor (in addition to existing LicenseRevocationService).
- Build the CRL: `List<LicenseToken> rows = revocationService.listRevokedSince(null);` then `List<String> jtis = rows.stream().map(LicenseToken::getJti).toList();`
- Build claims with Nimbus: `Instant now=Instant.now(); Instant next=now.plus(crlTtl); JWTClaimsSet claims=new JWTClaimsSet.Builder().issuer(issuer).claim("iat", now.getEpochSecond()).claim("nextUpdate", next.getEpochSecond()).claim("revoked", jtis).build();` — NOTE: use explicit numeric `iat` claim (epoch seconds) rather than issueTime(Date) so the verifier reads a plain number; or use .issueTime(Date.from(now)) and read getIssueTime() in the verifier (pick one and keep CrlVerifier consistent — spec assumes numeric seconds for iat AND a numeric nextUpdate).
- `String jws = jwsSigner.sign(claims, "crl+jwt");`
- Return `ResponseEntity.ok().header("Cache-Control","public, max-age="+crlTtl.toSeconds()).contentType(MediaType.valueOf("application/jwt")).body(jws);`
- Add imports: com.nimbusds.jwt.JWTClaimsSet, java.time.Instant, java.time.Duration, org.springframework.http.MediaType, org.springframework.http.ResponseEntity, com.example.cp.keys.JwsSigner, org.springframework.beans.factory.annotation.Value.

### [MODIFY] `control-panel-api/src/main/java/com/example/cp/auth/SecurityConfig.java`
- In the permitAll() requestMatchers list (currently includes "/api/v1/licenses/revoked" at line 69), add a new entry "/api/v1/licenses/crl" so the signed CRL endpoint is reachable by unauthenticated customer apps. Without this, /api/** falls through to .authenticated().

### [MODIFY] `control-panel-api/src/main/resources/application.yml`
- depends on: app.signing.crl-ttl
- Under `app.signing:` (after default-audience, line 42) add `crl-ttl: ${APP_CRL_TTL:PT1H}`.

### [NEW FILE] `license-verifier/src/main/java/com/example/licenseverifier/RevocationChecker.java`
- depends on: RevocationChecker (verifier lib)
- NEW public interface in package com.example.licenseverifier.
- `boolean isRevoked(String jti);`
- `default boolean isOperational() { return true; }` — used so a fail-closed checker can be distinguished; LicenseVerifier treats !isOperational() as: reject everything (revoked).
- `static RevocationChecker none()` returning a singleton no-op that always returns false from isRevoked and true from isOperational (preserves current behavior when no CRL configured).

### [NEW FILE] `license-verifier/src/main/java/com/example/licenseverifier/RevocationList.java`
- depends on: RevocationList (verifier lib model)
- NEW final class in package com.example.licenseverifier.
- Fields: String issuer; Instant issuedAt; Instant nextUpdate; Set<String> revokedJtis (unmodifiable copy).
- Constructor `RevocationList(String issuer, Instant issuedAt, Instant nextUpdate, Set<String> revokedJtis)`.
- Accessors issuer(), issuedAt(), nextUpdate(), revokedJtis().
- `boolean isRevoked(String jti){ return jti!=null && revokedJtis.contains(jti); }`
- `boolean isStale(Instant now, Duration maxStale){ return nextUpdate==null || now.isAfter(nextUpdate.plus(maxStale)); }`
- Add static EMPTY/uninitialized sentinel is NOT needed — starter holds null until first successful fetch.

### [NEW FILE] `license-verifier/src/main/java/com/example/licenseverifier/CrlVerifier.java`
- depends on: CrlVerifier (verifier lib), RevocationList (verifier lib model), SignedCrl (JWS schema)
- NEW final class in package com.example.licenseverifier.
- Constructor `CrlVerifier(PublicKeyProvider keyProvider, String expectedIssuer)` (expectedIssuer may be null to skip issuer check).
- Method `RevocationList verify(String crlJws)`: SignedJWT.parse; require header alg == JWSAlgorithm.EdDSA else LicenseSignatureInvalidException; require typ != null and equalsIgnoreCase "crl+jwt" else LicenseFileMalformedException; require kid present.
- Reuse the SAME verifier-resolution logic as LicenseVerifier.resolveVerifier(kid): if keyProvider instanceof PublicKeyProvider.JwkProvider, build Ed25519Verifier from the OctetKeyPair; else reconstruct from PublicKey (copy encodeEd25519PublicKey). RECOMMENDED: extract LicenseVerifier.resolveVerifier + encodeEd25519PublicKey into a package-private helper `Ed25519Verifiers` so both classes share it (avoids divergence). LicenseKidUnknownException on unknown kid.
- After signature verify: read claims. issuer check vs expectedIssuer (LicenseIssuerMismatchException if mismatch). Read iat = numericClaim("iat"); nextUpdate = numericClaim("nextUpdate"); revoked = (List<String>) claims.getClaim("revoked") (null -> empty). Convert epoch-seconds longs to Instant.ofEpochSecond. Build and return RevocationList.
- Throw LicenseFileMalformedException if revoked claim is present but not a List of strings, or nextUpdate missing.

### [NEW FILE] `license-verifier/src/main/java/com/example/licenseverifier/exceptions/LicenseRevokedException.java`
- depends on: LicenseRevokedException (verifier lib)
- NEW class extends LicenseException. Constructor `LicenseRevokedException(String jti)` -> super("License '"+jti+"' has been revoked"); store jti; getJti().

### [MODIFY] `license-verifier/src/main/java/com/example/licenseverifier/LicenseVerifier.java`
- depends on: RevocationChecker (verifier lib), LicenseRevokedException (verifier lib)
- MANDATORY EXP: in validateTemporalClaims (lines 201-219) change the `if (exp != null)` block so a missing exp is REJECTED. Add at top: `if (exp == null) throw new LicenseExpiredException("License is missing a mandatory exp claim", null);` (LicenseExpiredException already accepts a null Instant). This satisfies #2/#10 ‘MUST reject missing exp’. Keep the existing expiry comparison for the non-null case.
- REVOCATION: add field `private final RevocationChecker revocationChecker;` initialized in the private constructor from `b.revocationChecker != null ? b.revocationChecker : RevocationChecker.none()`.
- In verifyJwt, AFTER validateIssuer(claims) and BEFORE toLicense (line 147): compute jti = claims.getJWTID(); add a new step `checkRevocation(claims.getJWTID())`.
- Add private method `checkRevocation(String jti)`: `if (!revocationChecker.isOperational()) throw new LicenseRevokedException(jti==null?"<unknown>":jti);` (fail closed when CRL stale) then `if (jti != null && revocationChecker.isRevoked(jti)) throw new LicenseRevokedException(jti);`.
- Builder: add `private RevocationChecker revocationChecker;` field and `public Builder revocationChecker(RevocationChecker rc){ this.revocationChecker = Objects.requireNonNull(rc); return this; }`.
- If extracting shared verifier resolution into Ed25519Verifiers (recommended for CrlVerifier reuse), replace resolveVerifier/encodeEd25519PublicKey bodies with calls to the helper. Otherwise leave them and let CrlVerifier duplicate.
- Imports: add com.example.licenseverifier.exceptions.LicenseRevokedException.

### [MODIFY] `license-verifier/src/main/java/com/example/licenseverifier/PublicKeyProvider.java`
- No required change. NOTE for implementer: the package-private `JwkProvider` interface and the StaticProvider/UrlProvider are reused by CrlVerifier for crypto resolution. If Ed25519Verifiers helper is added, it lives in the same package and can reference JwkProvider. No API change needed here.

### [MODIFY] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/LicenseProperties.java`
- depends on: app.license.crl-url, app.license.crl-refresh-interval, app.license.crl-max-stale
- Add field `private String crlUrl;` (null/blank => revocation disabled) with getter/setter getCrlUrl/setCrlUrl.
- Add field `private Duration crlRefreshInterval = Duration.ofMinutes(15);` with getter/setter.
- Add field `private Duration crlMaxStale = Duration.ofHours(1);` with getter/setter.
- (Optional) add `private boolean crlFailClosed = true;` to let an operator opt the fail-closed behavior off; default true. Wire into CrlRevocationChecker if added.

### [NEW FILE] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/CrlRevocationChecker.java`
- depends on: CrlRevocationChecker (starter), RevocationChecker (verifier lib), CrlVerifier (verifier lib), RevocationList (verifier lib model), app.license.crl-url, app.license.crl-refresh-interval, app.license.crl-max-stale
- NEW class in package com.example.licenseverifier.spring implementing com.example.licenseverifier.RevocationChecker.
- Constructor `(CrlVerifier crlVerifier, LicenseProperties props)`; hold `AtomicReference<RevocationList> current = new AtomicReference<>();` and a java.net.http.HttpClient (mirror PublicKeyProvider.UrlProvider style).
- `void load()` performed once at startup by autoconfig (or @PostConstruct): call refresh(); if it fails and crlFailClosed, leave current=null (checker becomes non-operational => deny all). Log error.
- `@Scheduled(fixedDelayString="${app.license.crl-refresh-interval:PT15M}", initialDelayString="${app.license.crl-refresh-interval:PT15M}") void scheduledRefresh()` wrapping refresh() in try/catch (keep previous list on failure).
- `void refresh()`: HTTP GET props.getCrlUrl() (15s timeout); on non-2xx throw; `RevocationList rl = crlVerifier.verify(body);` then current.set(rl). On any exception: if current.get()==null rethrow (initial failure) else log and keep previous.
- `boolean isRevoked(String jti)`: `RevocationList rl=current.get(); if(rl==null) return true; if(rl.isStale(Instant.now(), props.getCrlMaxStale())) return true; return rl.isRevoked(jti);` (fail closed).
- `boolean isOperational()`: `RevocationList rl=current.get(); return rl!=null && !rl.isStale(Instant.now(), props.getCrlMaxStale());`
- Use a daemon single-thread executor only if not relying on Spring @Scheduled; prefer @Scheduled since starter already @EnableScheduling.

### [MODIFY] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/LicenseVerifierAutoConfiguration.java`
- depends on: CrlRevocationChecker (starter), RevocationChecker (verifier lib), CrlVerifier (verifier lib)
- FIX PRE-EXISTING COMPILE BUG: line 30 `builder.publicKeysFromUrl(props.getRefreshFromUrl());` passes a String but Builder.publicKeysFromUrl expects a java.net.URL. Wrap as `builder.publicKeysFromUrl(java.net.URI.create(props.getRefreshFromUrl()).toURL())` (handle MalformedURLException) OR add a String overload to LicenseVerifier.Builder. This bug must be fixed for the module to compile; the new CRL code shares the same JWKS provider so it surfaces here.
- REFACTOR licenseVerifier bean so the JWKS PublicKeyProvider is created ONCE and shared between the LicenseVerifier and the CrlVerifier (both must verify against the same JWKS). Extract a `@Bean @ConditionalOnMissingBean PublicKeyProvider licenseKeyProvider(LicenseProperties props)` that builds from URL or classpath:/jwks.json; have licenseVerifier(...) consume that provider via a new builder method `publicKeys(PublicKeyProvider)` (already exists).
- Wire revocation into the verifier bean: inject the new `RevocationChecker` bean and call `builder.revocationChecker(rc)`.
- Add `@Bean @ConditionalOnMissingBean RevocationChecker revocationChecker(LicenseProperties props, ObjectProvider<PublicKeyProvider> keyProvider)`: if props.getCrlUrl() is null/blank return RevocationChecker.none(); else build `CrlVerifier crl = new CrlVerifier(keyProvider.getObject(), props.getIssuer());` and return a new CrlRevocationChecker(crl, props), then call its load() before returning (so startup fails closed/strict appropriately).
- Ensure bean ordering: revocationChecker depends on the shared PublicKeyProvider bean; licenseVerifier depends on RevocationChecker. @EnableScheduling already present so CrlRevocationChecker.@Scheduled works.
- Imports: java.net.URI/URL, com.example.licenseverifier.RevocationChecker, com.example.licenseverifier.CrlVerifier, com.example.licenseverifier.PublicKeyProvider, org.springframework.beans.factory.ObjectProvider.

### [MODIFY] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/RequiresPermissionAspect.java`
- depends on: RevocationChecker (verifier lib)
- No new dependency needed if revocation is enforced inside LicenseService.status()/current() (preferred — single chokepoint). The aspect already denies on NOT_LOADED/EXPIRED. Add handling for a new REVOKED status: after the existing status checks, treat LicenseService.Status.REVOKED like NOT_LOADED/EXPIRED -> throw LicensePermissionDeniedException(requiredCode(rp), "License has been revoked").
- Concretely: change the first guard (lines 27-33) condition to also include `|| status == LicenseService.Status.REVOKED` and include status in the message.

### [MODIFY] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/LicenseService.java`
- depends on: RevocationChecker (verifier lib)
- Add a new enum constant `REVOKED` to LicenseService.Status (after READ_ONLY).
- Inject the RevocationChecker: add constructor param `RevocationChecker revocationChecker` and field. Update the autoconfig licenseService bean factory accordingly (it currently calls `new LicenseService(verifier, props)`).
- In status(): after loading current license and before expiry checks, add: `if (lic.jti()!=null && (!revocationChecker.isOperational() || revocationChecker.isRevoked(lic.jti()))) return Status.REVOKED;` — this is the runtime chokepoint that makes already-loaded licenses become denied as soon as the CRL lists them or the CRL goes stale (fail closed). Place this BEFORE the isExpired branch so revocation wins over READ_ONLY.
- Note: verify() at load time already rejects a revoked jti (via LicenseVerifier+RevocationChecker), so a license revoked before load never enters cache; this status() check covers licenses revoked AFTER they were loaded.

### [MODIFY] `license-verifier-spring-boot-starter/src/main/java/com/example/licenseverifier/spring/LicenseVerifierAutoConfiguration.java (licenseService bean)`
- depends on: RevocationChecker (verifier lib)
- Update `licenseService(LicenseVerifier verifier, LicenseProperties props)` bean to also take `RevocationChecker revocationChecker` and call `new LicenseService(verifier, props, revocationChecker)`. (Same file as above — listed separately to flag the constructor signature change ripple.)

### [MODIFY] `sample-docker-app/src/main/resources/application.yml`
- depends on: app.license.crl-url, app.license.crl-refresh-interval
- Under `app.license:` add `crl-url: ${LICENSE_CRL_URL:}` , `crl-refresh-interval: ${LICENSE_CRL_REFRESH:PT15M}`, `crl-max-stale: ${LICENSE_CRL_MAX_STALE:PT1H}`. Blank crl-url keeps revocation disabled by default so existing sample behavior is unchanged unless configured.

### [MODIFY] `docs/api/openapi.yaml`
- depends on: SignedCrl (JWS schema)
- Document the new GET /api/v1/licenses/crl: security none (public), response 200 application/jwt, body = compact JWS string; describe the claims schema {iss,iat,nextUpdate,revoked[]} and typ=crl+jwt. (Non-blocking for compile.)

## Tests to add

- license-verifier LicenseVerifierTest: add test `missing_exp_is_rejected` — sign a license with NO expirationTime and assert verify() throws LicenseExpiredException (proves mandatory exp #2/#10). NOTE: existing tests always set exp via signLicense, so none regress; the `clock_skew_tolerates_slight_expiration` test still passes.
- license-verifier (new RevocationVerifierTest): build verifier with `.revocationChecker(jti -> jti.equals("lic_test_001"))`; verify a valid-but-revoked license throws LicenseRevokedException; verify a non-revoked jti passes.
- license-verifier RevocationVerifier fail-closed test: `.revocationChecker` whose isOperational() returns false -> verify() throws LicenseRevokedException even for a non-listed jti.
- license-verifier (new CrlVerifierTest): generate Ed25519 key + JWKS (reuse OctetKeyPairGenerator pattern from LicenseVerifierTest); hand-sign a crl+jwt JWS with claims {iss,iat,nextUpdate,revoked:["lic_a","lic_b"]}; assert CrlVerifier.verify returns RevocationList with those jtis, correct issuer/nextUpdate. Negative tests: tampered signature -> LicenseSignatureInvalidException; wrong typ (license+jwt) -> LicenseFileMalformedException; unknown kid -> LicenseKidUnknownException; wrong issuer -> LicenseIssuerMismatchException; stale list (nextUpdate in past) -> isStale(now, skew)==true.
- starter (new CrlRevocationCheckerTest): mock CrlVerifier / serve a fixed CRL JWS over a local HttpServer; assert isRevoked true for listed jti, false for unlisted; after forcing stale (set nextUpdate in past) isOperational()==false and isRevoked(any)==true (fail closed); when crl-url blank, autoconfig wires RevocationChecker.none() and nothing is denied.
- starter RequiresPermissionAspectTest: add cases — when licenseService.status() returns REVOKED, guarded() and readOnlyGuarded() both throw LicensePermissionDeniedException with message containing REVOKED.
- starter LicenseServiceTest (new or extend): with a mocked RevocationChecker returning isRevoked(true) for the loaded license jti, status() returns REVOKED (overrides ACTIVE/READ_ONLY); with isOperational()==false status() returns REVOKED; with checker none() status() unchanged from current behavior.
- control-panel-api (new CrlControllerTest, @WebMvcTest or slice): GET /api/v1/licenses/crl returns 200 application/jwt; the body parses as a SignedJWT with header typ=crl+jwt, alg=EdDSA, kid=active; signature verifies against the active public key from KeyService; claims contain iss=app.signing.issuer and revoked = all REVOKED jti from the repo; Cache-Control max-age == crl-ttl seconds.
- control-panel-api end-to-end: revoke a license via POST /licenses/{jti}/revoke, then GET /crl and assert the jti now appears in the revoked array and the JWS still verifies.
- control-panel-api SecurityConfig test: GET /api/v1/licenses/crl is reachable without authentication (permitAll) — 200 not 401.

## Risks / cross-file notes

- COMPILE BLOCKER (pre-existing): LicenseVerifierAutoConfiguration line 30 calls builder.publicKeysFromUrl(String) but the only Builder overloads take java.net.URL. The starter currently only compiles because nothing exercises that branch at compile time? No — it is a hard javac error if present. Verify by building; the fix (URI.create(...).toURL() or a new String overload) is REQUIRED before the CRL wiring will compile. Handle MalformedURLException.
- The starter module has NO direct nimbus-jose-jwt dependency (it inherits it transitively from license-verifier). All Nimbus usage in the new CrlVerifier lives in the license-verifier module (which does depend on Nimbus) — keep JWS parsing there, not in the starter, to avoid needing a new dependency. CrlRevocationChecker in the starter must only touch RevocationList/CrlVerifier (no direct Nimbus types).
- iat/nextUpdate encoding must match between CrlController (writer) and CrlVerifier (reader). Decide ONE: numeric epoch-seconds claims (recommended; matches spec) — then CrlController must NOT use .issueTime()/.expirationTime() (which Nimbus serializes as numbers too, but reading via getIssueTime() returns Date). Simplest consistent choice: write `.claim("iat", now.getEpochSecond())` and `.claim("nextUpdate", next.getEpochSecond())` and read them as numbers in CrlVerifier. Mismatch = NPE/parse failure at runtime.
- Fail-closed must not deadlock startup: with app.license.strict and crl-fail-closed both true, an unreachable CRL at boot will make CrlRevocationChecker.load() rethrow -> context fails to start. That is the intended fail-closed posture, but confirm sample-docker-app defaults (strict=false, crl-url blank) so existing demos still start. Document that enabling crl-url implies the app needs CRL reachability at boot.
- Shared PublicKeyProvider: LicenseVerifier and CrlVerifier MUST use the same JWKS source/instance. If two separate UrlProvider instances are created, you get two refresh threads and possible key-set drift right after rotation. Refactor autoconfig to build the provider once and inject it into both. UrlProvider.fromJwksUrl already self-refreshes on a daemon thread.
- Verifier resolution duplication: CrlVerifier needs the exact Ed25519 verifier-resolution logic currently private in LicenseVerifier (resolveVerifier + encodeEd25519PublicKey). Duplicating risks divergence (e.g., the JwkProvider preferred path). Strongly prefer extracting a package-private helper class Ed25519Verifiers in com.example.licenseverifier and having both call it. This is an extra refactor touching LicenseVerifier.
- Backward compatibility of RevocationChecker default: LicenseVerifier.Builder must default to RevocationChecker.none() so the 12 existing LicenseVerifierTest cases (which never set a checker) keep passing. Verify the private constructor null-guards correctly.
- Mandatory-exp change is behavior-breaking for any caller relying on perpetual (no-exp) licenses. The control-panel issuer ALWAYS sets exp (LicenseClaimsBuilder line 90), so server-issued licenses are fine; only hand-rolled/legacy licenses without exp will now be rejected. This is the intended hardening (#2/#10) but call it out.
- CrlController order of @PreAuthorize vs SecurityConfig: /crl is under /api/** which is .authenticated() by default; the permitAll in SecurityConfig (new "/api/v1/licenses/crl" matcher) is what actually opens it. The method-level @PreAuthorize("permitAll()") alone is insufficient because @EnableMethodSecurity runs AFTER the filter chain authn — without the requestMatcher the request is rejected with 401 before reaching the method. Both are needed (matcher is the load-bearing one).
- listRevokedSince(null) returns ALL REVOKED tokens including long-expired ones; the revoked array can grow unbounded over time. P0-acceptable, but note for a follow-up: prune jti whose expires_at < now from the CRL (a revoked-but-already-expired license is already rejected by mandatory exp), which keeps the signed CRL small.
- Clock-skew interaction: starter LicenseService.status() new REVOKED check uses Instant.now() for staleness via props.getCrlMaxStale(); ensure crl-max-stale >= crl-refresh-interval (default 1h >= 15m) or a healthy CRL could be flagged stale between refreshes, denying all calls. Add a startup validation/log warning if crl-max-stale < crl-refresh-interval.
