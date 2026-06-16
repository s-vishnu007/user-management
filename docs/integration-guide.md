# License SDK Integration Guide

**Who this is for:** application developers shipping a Dockerized product who need to enforce the entitlements granted by a `.lic` file issued by the control panel.

There are two SDKs, depending on your stack:

| If your app is... | Use |
|---|---|
| Spring Boot 3.x | `license-verifier-spring-boot-starter` (covered first) |
| Plain Java / Java EE / Quarkus / Micronaut | `license-verifier` (plain SDK, covered second) |
| Node.js / Python / Go / .NET | No native SDK in Phase 1. Use the [language-agnostic recipe](#non-jvm-applications) at the bottom. |

---

## Part 1 — Spring Boot integration

### 1.1 Add the dependency

In your `pom.xml`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>license-verifier-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Or Gradle:

```kotlin
implementation("com.example:license-verifier-spring-boot-starter:0.1.0")
```

The starter pulls in `nimbus-jose-jwt` transitively. JDK 17+ is required (it uses the native Ed25519 provider).

### 1.2 Configure `application.yml`

```yaml
app:
  license:
    # Where the .lic file lives inside the container. Mount your downloaded
    # license file at this path.
    path: /etc/app/license.lic

    # Required: must appear in the JWT's `aud` array.
    audience: docker-app-prod

    # Optional: if set, the verifier requires an exact match on the `iss` claim.
    issuer: https://control-panel.example.com

    # Optional: fetch and cache the JWKS from a URL. If left empty, the verifier
    # loads the static JWKS bundled at `src/main/resources/jwks.json`.
    refresh-from-url: https://control-panel.example.com/.well-known/jwks.json

    # ISO-8601 durations.
    refresh-interval: PT24H
    clock-skew: PT5M

    # If true, an expired license keeps the app running but flips LicenseService.status() to READ_ONLY.
    read-only-on-expiry: true

    # If true, refuse to start without a valid license. Recommended in prod.
    strict: true
```

All values are env-var overridable with the standard Spring Boot relaxed binding (e.g. `APP_LICENSE_PATH`, `APP_LICENSE_AUDIENCE`). The bundled JWKS is always loaded from `classpath:/jwks.json` — replace that file in your image build if you don't want to use `refresh-from-url`.

### 1.3 Install the public JWKS

Pick one of these:

- **Static (air-gapped friendly).** Download the JWKS from the control panel at build time and place it at `src/main/resources/jwks.json`. Point `app.license.jwks-classpath` at it. Re-bake when keys rotate.
- **Dynamic (recommended when network egress is allowed).** Set `app.license.refresh-from-url` to `https://<control-panel>/.well-known/jwks.json`. The starter polls it on the configured interval and caches the result. The classpath JWKS is still loaded as a fallback when the URL is unreachable.

The verifier picks the right key by `kid` from the JWT header, so both old and new keys coexist during rotation windows.

### 1.4 Gate endpoints with `@RequiresPermission`

```java
@RestController
@RequestMapping("/api")
public class ExportController {

    @RequiresPermission("export.pdf")
    @PostMapping("/exports")
    public ExportResponse export(@RequestBody ExportRequest req) {
        // ...
    }

    @RequiresPermission("export.csv")
    @GetMapping("/exports/{id}.csv")
    public ResponseEntity<Resource> downloadCsv(@PathVariable String id) {
        // ...
    }
}
```

When the licensed entitlements don't include the requested permission the aspect returns `403 Forbidden` with a JSON `ProblemDetail` body.

You can also accept any one of several permissions:

```java
@RequiresPermission(anyOf = {"admin.users.invite", "admin.users.manage"})
@PostMapping("/admin/users")
public User createUser(...) { ... }
```

If the endpoint is safe to call once the license has expired (read-only operations), opt it in with `readOnly = true`:

```java
@RequiresPermission(value = "report.read", readOnly = true)
@GetMapping("/reports/{id}")
public Report read(...) { ... }
```

### 1.5 Query the license programmatically

When you need data, not a gate:

```java
import com.example.licenseverifier.License;
import com.example.licenseverifier.spring.LicenseService;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final LicenseService licenseService;

    public boolean canCreateUser(int currentUserCount) {
        return licenseService.currentOptional()
            .map(lic -> {
                Integer cap = lic.feature("max_users", Integer.class);
                return cap == null || currentUserCount < cap;
            })
            .orElse(false);
    }

    public boolean aiEnabled() {
        return licenseService.currentOptional()
            .map(lic -> Boolean.TRUE.equals(lic.feature("ai_assistant", Boolean.class)))
            .orElse(false);
    }
}
```

### 1.6 Expose license status to ops

The starter registers a Spring Boot Actuator endpoint at `/actuator/license`. Enable it:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, license
```

The response shape:

```json
{
  "status": "ACTIVE",
  "plan": "pro",
  "issuedAt": "2026-06-01T10:00:00Z",
  "expiresAt": "2027-06-01T10:00:00Z",
  "seats": 25,
  "permissions": ["export.pdf", "api.v2", "admin.users.invite"],
  "features": { "max_users": 50, "ai_assistant": true },
  "customer": { "org_name": "Example Corp" }
}
```

`status` is one of `ACTIVE`, `EXPIRED`, `MISSING`, `INVALID`, `READ_ONLY`.

### 1.7 Mount the license file in Docker

```dockerfile
# (Your application Dockerfile)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/your-app.jar /app/app.jar
ENV LICENSE_PATH=/etc/app/license.lic \
    LICENSE_AUDIENCE=docker-app-prod \
    LICENSE_ISSUER=https://control-panel.example.com \
    LICENSE_STRICT=true
EXPOSE 8080
VOLUME ["/etc/app"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

And at runtime:

```bash
docker run --rm \
  -p 8080:8080 \
  -v "$(pwd)/license:/etc/app:ro" \
  your-app:latest
```

Place `license.lic` in `./license/license.lic`.

For Kubernetes, use a `Secret` mounted as a file:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-license
type: Opaque
data:
  license.lic: <base64-encoded .lic file>
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: your-app }
spec:
  template:
    spec:
      containers:
        - name: app
          image: your-app:latest
          volumeMounts:
            - name: license
              mountPath: /etc/app
              readOnly: true
      volumes:
        - name: license
          secret:
            secretName: app-license
```

### 1.8 Handling expiry gracefully

By default, an expired license fails startup in strict mode. To degrade to read-only instead:

```yaml
app:
  license:
    strict: false
    on-expiry: READ_ONLY  # or REJECT (default)
```

In `READ_ONLY` mode the verifier accepts the license but flips `status` to `EXPIRED`. Your code can check `licenseService.isReadOnly()` and refuse writes. The actuator endpoint surfaces `EXPIRED` so your monitoring catches it.

### 1.9 Reporting usage events (optional)

If the customer is online and you want to feed the control panel's usage dashboards, the starter can ship batched events:

```java
@Autowired UsageReporter usage;

usage.record("export.pdf", 1, Map.of("size_bytes", 124000));
```

Events are queued and POSTed to `/api/v1/usage/ingest` in batches; failures retry with exponential backoff and never block your request thread. Configure with:

```yaml
app:
  license:
    usage:
      enabled: true
      endpoint: https://control-panel.example.com/api/v1/usage/ingest
      flush-interval: 30s
      max-batch-size: 500
```

---

## Part 2 — Plain Java integration

For apps that don't run Spring.

### 2.1 Dependency

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>license-verifier</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2.2 Build the verifier once at startup

```java
LicenseVerifier verifier = LicenseVerifier.builder()
    .publicKeysFromClasspath("/jwks.json")
    .audience("docker-app-prod")
    .issuer("https://control-panel.example.com")
    .clockSkew(Duration.ofMinutes(5))
    .build();

String envelope = Files.readString(Path.of("/etc/app/license.lic"));
License license = verifier.verify(envelope);

if (!license.hasPermission("export.pdf")) {
    throw new IllegalStateException("PDF export is not part of your subscription.");
}
```

### 2.3 Reusing the verifier

The verifier is thread-safe and cached after construction. Build it once and inject it.

### 2.4 Refreshing the JWKS at runtime

```java
JwksProvider jwks = JwksProvider.fromUrl(
    URI.create("https://control-panel.example.com/.well-known/jwks.json"),
    Duration.ofHours(24)
);
LicenseVerifier verifier = LicenseVerifier.builder()
    .publicKeys(jwks)
    .audience("docker-app-prod")
    .build();
```

`JwksProvider.fromUrl` caches the result and refreshes on the configured interval. Failed refreshes keep the previous cache and retry with backoff.

### 2.5 Querying features

```java
license.feature("max_users", Integer.class)
    .ifPresent(cap -> ensureUserCountUnder(cap));

if (license.feature("ai_assistant", Boolean.class).orElse(false)) {
    enableAiRoutes();
}
```

---

## Part 3 — Non-JVM applications

For Node/Python/Go/etc., there's no native SDK yet. The verification recipe is short, though, because EdDSA JWTs are well-supported across languages.

### 3.1 What you need

1. The control panel's JWKS: `GET https://<control-panel>/.well-known/jwks.json`. Cache it.
2. The `.lic` file mounted at a known path.
3. A JOSE library in your language that supports EdDSA (Ed25519).

### 3.2 Steps (algorithm-agnostic)

```
1. Read the file at LICENSE_PATH (UTF-8 JSON).
2. Parse the envelope, extract `license` (a JWT string).
3. Decode the JWT header. Reject if alg != "EdDSA" or typ != "license+jwt".
4. Find the JWK in the JWKS where kid == header.kid.
5. Verify the Ed25519 signature against the JWT's signing input.
6. Reject if now < nbf - skew or now > exp + skew (default skew: 5 minutes).
7. Reject if your configured audience is not in claims.aud[].
8. Reject if claims.iss != configured issuer (when configured).
9. Reject if claims.version != 1.
10. Cache claims in memory. Gate endpoints on claims.permissions[].
```

### 3.3 Node.js recipe (jose v5)

```javascript
import * as jose from 'jose';
import { readFileSync } from 'node:fs';

const envelope = JSON.parse(readFileSync(process.env.LICENSE_PATH, 'utf-8'));
const jwks = jose.createRemoteJWKSet(
  new URL('https://control-panel.example.com/.well-known/jwks.json')
);

const { payload } = await jose.jwtVerify(envelope.license, jwks, {
  issuer: 'https://control-panel.example.com',
  audience: 'docker-app-prod',
  algorithms: ['EdDSA'],
  clockTolerance: '5m',
});

if (payload.version !== 1) throw new Error('Unsupported license version');
const permissions = new Set(payload.permissions ?? []);
```

### 3.4 Python recipe (PyJWT + cryptography)

```python
import json, jwt, urllib.request
from datetime import timedelta

with open(os.environ["LICENSE_PATH"]) as f:
    envelope = json.load(f)

jwks_client = jwt.PyJWKClient("https://control-panel.example.com/.well-known/jwks.json")
signing_key = jwks_client.get_signing_key_from_jwt(envelope["license"])

claims = jwt.decode(
    envelope["license"],
    signing_key.key,
    algorithms=["EdDSA"],
    audience="docker-app-prod",
    issuer="https://control-panel.example.com",
    leeway=timedelta(minutes=5),
)

assert claims["version"] == 1
permissions = set(claims["permissions"])
```

### 3.5 Go recipe (`github.com/lestrrat-go/jwx/v2`)

```go
keyset, _ := jwk.Fetch(ctx, "https://control-panel.example.com/.well-known/jwks.json")
data, _ := os.ReadFile(os.Getenv("LICENSE_PATH"))
var envelope struct{ License string `json:"license"` }
json.Unmarshal(data, &envelope)

tok, err := jwt.Parse(
    []byte(envelope.License),
    jwt.WithKeySet(keyset),
    jwt.WithAudience("docker-app-prod"),
    jwt.WithIssuer("https://control-panel.example.com"),
    jwt.WithAcceptableSkew(5*time.Minute),
)
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App fails to start with "no license at /etc/app/license.lic" | Volume not mounted, or `LICENSE_PATH` wrong | Check `docker run -v ...` or your K8s `Secret` mount |
| "Unknown kid `key-2026-06-01`" | Bundled JWKS is stale | Re-download the JWKS, rebuild the image; or set `app.license.refresh-from-url` |
| "Audience mismatch" | Wrong `LICENSE_AUDIENCE` value | Match exactly what was issued (`docker-app-prod`, `docker-app-staging`, etc.) |
| 403 on a permission you expect to have | Subscription doesn't grant it, or override was missed | Inspect `/actuator/license` -> `permissions[]`; reissue if needed |
| Verifier rejects valid license under heavy clock drift | Skew too tight | Raise `app.license.clock-skew` |
| `EXPIRED` status with no recent rotation | Subscription expired | Renew via control panel, replace `license.lic` |

---

## Reference

- [License file format spec](./license-format.md) — the wire contract
- [Control plane OpenAPI](./api/openapi.yaml) — issuance, revocation, JWKS endpoints
- [Sample Docker app](../sample-docker-app/README.md) — copy-pasteable working example
