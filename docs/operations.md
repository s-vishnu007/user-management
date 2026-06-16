# Build, Configuration & Deployment (Operations Guide)

This document is the operator's and build engineer's reference for the **user-management control panel** system. It covers the Maven reactor (parent POM + four module POMs, dependency management, plugins, and the OWASP/CVE gate), the container build (`docker-compose.yml`, the three Dockerfiles, and the nginx template), CI/CD (`.github/workflows/ci.yml` + `.github/dependabot.yml`), and the runtime configuration of the API (`application*.yml` + `logback-spring.xml`) and the sample consumer app. It ends with a complete **environment-variable reference** so a deployment can be wired up without reading the YAML by hand.

The system is a Spring Boot 3.3 / Java 21 multi-module Maven project. The **control panel** (`control-panel-api`) is the server that provisions subscriptions and *issues* Ed25519-signed JWT licenses; the **license-verifier** SDK and its **Spring Boot starter** let a customer's Dockerized product *verify* those licenses fully offline; **sample-docker-app** is a reference consumer; **admin-ui** is the React SPA. Everything is wired together at runtime by `docker-compose.yml`.

## How it fits the bigger picture

Two trust domains meet here. The control panel holds the **private** Ed25519 signing keys (encrypted at rest with an AES KEK supplied via `APP_KEY_ENC_MASTER`) and a PostgreSQL database of orgs, subscriptions, and licenses; it runs behind a TLS-terminating proxy on the `backend` Docker network with PostgreSQL and an authenticated Redis. The customer app only ever sees **public** key material (a JWKS) and a signed CRL, both fetchable from the panel, so it can verify a `.lic` file with no network call at request time. The build/deploy layer documented here is what guarantees those two domains stay isolated (network segmentation in compose), reproducible (pinned image tags + locked dependency versions + the OWASP gate), and fail-safe (every production secret is a *required* env var with no baked-in default, so a misconfigured deploy aborts at startup rather than running with well-known credentials).

---

# 1. The Maven Reactor

## 1.1 `pom.xml` (parent — `com.example:user-management-parent:0.1.0-SNAPSHOT`)

**Responsibility:** the reactor aggregator and the single source of truth for the Spring Boot platform version, all third-party dependency versions (via `<dependencyManagement>`), shared build-plugin configuration (via `<pluginManagement>`), and the security-critical transitive-dependency overrides. Packaging is `pom`.

### Parent platform

```
spring-boot-starter-parent : 3.3.13
```

This is the **final patch of the 3.3.x line** (3.3.x OSS support ended 2025-06-30). The choice is deliberate and documented inline: staying on the same minor keeps Tomcat/Spring/Spring-Security/Logback at their newest 3.3-managed patches with minimal regression risk. A move to a supported line (3.4/3.5) is tracked out-of-band — it *also* has to migrate SAML off the EOL OpenSAML 4 stack, so it is intentionally **not** done here. Dependabot is configured to honor this (it ignores major/minor bumps of the parent — see §4.2).

### Aggregated modules

| Module | Artifact | Role |
|---|---|---|
| `control-panel-api` | `control-panel-api` | The REST API server (issues licenses). |
| `license-verifier` | `license-verifier` | Plain-Java offline verification SDK. |
| `license-verifier-spring-boot-starter` | `license-verifier-spring-boot-starter` | Auto-config + `@RequiresPermission` AOP wrapping the SDK. |
| `sample-docker-app` | `sample-docker-app` | Reference consumer of the starter. |

> The reactor lists four modules; `admin-ui` is a separate Vite/npm project (not a Maven module) and is built independently in CI and in its own Dockerfile.

### Properties (`<properties>`)

| Property | Value | Why |
|---|---|---|
| `java.version`, `maven.compiler.source`/`target` | `21` | Java 21 across all modules. |
| `project.build.sourceEncoding` / `project.reporting.outputEncoding` | `UTF-8` | Deterministic, locale-independent builds. |
| `nimbus-jose-jwt.version` | `9.40` | JOSE/JWT library; signs & verifies the Ed25519 (EdDSA) license JWTs. |
| `lombok.version` | `1.18.40` | Used as an annotation processor (see compiler config). |
| `springdoc-openapi.version` | `2.6.0` | OpenAPI/Swagger UI (disabled in prod). |
| `bucket4j.version` | `0.12.8` | Rate-limiting library (the *starter* is present but disabled — see note below). |
| `totp.version` | `1.7.1` | `dev.samstevens.totp` — RFC-6238 TOTP for MFA. |
| `logstash-logback-encoder.version` | `7.4` | JSON log encoder used by `logback-spring.xml`. |
| `testcontainers.version` | `1.20.3` | Imported as a BOM (see below). |
| `postgresql.version` | `42.7.7` | **Bumped 42.7.4 → 42.7.7** to pick up pgjdbc security/bugfix patches (covers CVE-2025-49146 et al.); same 42.7.x line, drop-in. |
| `json-smart.version` | `2.5.2` | **SCA override** for CVE-2024-57699 (transitively via Nimbus/OAuth2). |
| `bouncycastle.version` | `1.78.1` | **SCA override** for DoS/timing/LDAP CVEs in 1.72 (transitively via OpenSAML / spring-security-saml2). |
| `uuid-creator.version` | `6.0.0` | UUIDv7 generation for sortable primary keys. |
| `aspectjweaver.version` | `1.9.22.1` | AOP weaving (used by `@RequiresPermission` / audit aspects). |
| `tink.version` | `1.13.0` | Google Tink — **required at runtime by Nimbus for Ed25519**; Nimbus declares it optional, so it is pinned and declared explicitly in the modules that sign/verify. |
| `jacoco-maven-plugin.version` | `0.8.14` | Coverage. |
| `byte-buddy.version` | `1.18.8` | **Override** of Spring Boot's managed 1.14.x so Mockito can instrument under newer local JDKs (e.g. JDK 24). Backward-compatible with JDK 21. |

### `<dependencyManagement>` — version pinning, not inclusion

This block only *manages* versions; child modules still declare the dependencies they actually use (usually without a `<version>`). It pins all the properties above plus two things worth calling out:

- **`testcontainers-bom`** is imported (`<type>pom</type><scope>import</scope>`), so any Testcontainers artifact a module pulls (e.g. `junit-jupiter`, `postgresql`) resolves to `1.20.3` without per-artifact versions.
- **The two SCA overrides (`json-smart`, BouncyCastle `bcprov-jdk18on` + `bcpkix-jdk18on`)** are forced here so *every* module resolves the patched coordinate even though they arrive transitively. Both BouncyCastle jars are pinned to the same coordinate set so the provider and PKIX jars never drift apart.

### `<build>` — shared plugin configuration

`<pluginManagement>` (config inherited by children that *declare* the plugin):

1. **`spring-boot-maven-plugin`** — declared so children can repackage executable jars.
2. **`maven-compiler-plugin`** — sets `source`/`target` to 21 and, critically, registers **Lombok as an annotation processor** via `annotationProcessorPaths`. (Modules add Lombok with `provided`/`optional` scope and *exclude* it from the repackaged jar — see the module POMs.)
3. **`maven-surefire-plugin`** (unit tests) — pins the **forked test JVM to UTC** via `<systemPropertyVariables><user.timezone>UTC</user.timezone>`. There is an important comment here: surefire deliberately uses `systemPropertyVariables` and **not** `<argLine>`, because JaCoCo's `prepare-agent` goal injects the coverage agent through the `argLine` property that Surefire auto-prepends. Defining `<argLine>` would clobber that and the agent would silently never attach (→ zero coverage). The UTC pin avoids host JVMs that report the legacy Olson id `Asia/Calcutta`, which PostgreSQL rejects as an invalid `TimeZone` on connect.
4. **`jacoco-maven-plugin`** — two executions: `prepare-agent` (default `initialize` phase, sets the `argLine`) and `report` (bound to `test`). **Report-only**: there is no `check` goal / coverage threshold, so coverage never fails the build.

`<plugins>` (active for *every* module):

- **JaCoCo** is activated reactor-wide so coverage is produced on `mvn test`/`verify`.
- **`maven-failsafe-plugin`** is bound here (Spring Boot's parent manages its version but binds no execution). It runs `*IT` integration tests (Testcontainers) on `mvn verify` with goals `integration-test` + `verify`, and mirrors the same **UTC** system-property pin. Modules without `*IT` classes simply no-op.

### `<repositories>`

A single extra repo: **Shibboleth Releases** (`https://build.shibboleth.net/maven/releases/`, releases only, snapshots off). Required to resolve OpenSAML artifacts pulled in by `spring-security-saml2-service-provider`.

> **Gotcha — the bucket4j starter is intentionally disabled.** The `bucket4j-spring-boot-starter` is a managed dependency, but the app uses a *custom* in-memory `RateLimitFilter` built on the bucket4j **core** API. The starter's auto-config would fail at startup with `NoCacheConfiguredException`, so `application.yml` sets `bucket4j.enabled: false`. Don't "fix" the missing cache config — disable is the intended state.

---

## 1.2 `control-panel-api/pom.xml`

**Responsibility:** the server application. `<finalName>control-panel-api</finalName>`, so the repackaged artifact is `target/control-panel-api.jar` (the Dockerfile copies exactly that name).

**Runtime stack (Spring Boot starters):** `web`, `security`, `data-jpa`, `validation`, `actuator`, `oauth2-client`, `data-redis`. Plus `spring-security-saml2-service-provider` for SAML SSO.

**Persistence & migrations:** `postgresql` (runtime scope, pinned to 42.7.7 via parent), `liquibase-core` (schema migrations driven by `classpath:db/changelog/db.changelog-master.yaml`; Hibernate is `ddl-auto: validate`, so Liquibase owns the schema and Hibernate only checks it).

**Crypto / licensing:** `nimbus-jose-jwt` + **`tink`** (Tink is required for Ed25519 even though Nimbus marks it optional — this is the recurring trap; it must be a real runtime dep).

**API docs / rate limit / MFA:** `springdoc-openapi-starter-webmvc-ui`, `bucket4j-spring-boot-starter` (disabled at runtime, see above), `dev.samstevens.totp` (declared *without* a version — managed by parent).

**Observability:** `micrometer-registry-prometheus` (backs `/actuator/prometheus`) and `logstash-logback-encoder` (JSON logs via `logback-spring.xml`).

**Utilities:** `uuid-creator` (UUIDv7), `aspectjweaver` (AOP), Lombok (`provided`).

**Test scope:** `spring-boot-starter-test`, `spring-security-test`, Testcontainers `junit-jupiter` + `postgresql`, and — notably — `com.example:license-verifier` (test scope). The panel pulls in its own verifier SDK *in tests only* to exercise the real offline verifier against the panel's live JWKS + signed CRL in cross-module integration tests. This is the contract test that proves "what we sign, the SDK can verify."

**Build:** declares `spring-boot-maven-plugin` and **excludes Lombok from the fat jar** (Lombok is compile-time only; shipping it bloats the jar and serves no runtime purpose).

---

## 1.3 `license-verifier/pom.xml`

**Responsibility:** the dependency-light, framework-free offline verification SDK (`com.example:license-verifier`, name "License Verifier SDK"). No Spring at all — so it can be embedded in any JVM app.

Redeclares `maven.compiler.source/target = 21` locally (harmless; already inherited).

**Dependencies:** `nimbus-jose-jwt` + **`tink`** (Ed25519 verification; same optional-but-required caveat), `jackson-databind` + `jackson-datatype-jsr310` (parse claims incl. `java.time`), `slf4j-api` (logging facade only — no binding, the host app supplies one), Lombok (`provided`). Tests use JUnit Jupiter + AssertJ.

**Gotcha:** keep this module's dependency footprint minimal. It is consumed by `license-verifier-spring-boot-starter` and embedded into customer apps; every transitive dependency here becomes a customer's problem.

---

## 1.4 `license-verifier-spring-boot-starter/pom.xml`

**Responsibility:** Spring Boot auto-configuration + the `@RequiresPermission` AOP enforcement that wraps the SDK so consumer apps get drop-in license enforcement.

**Dependencies:**
- `com.example:license-verifier` (`${project.version}`) — the SDK it wraps.
- `spring-boot-starter`, `spring-boot-autoconfigure`, `spring-boot-starter-aop` — auto-config + AspectJ-style advice for `@RequiresPermission`.
- `spring-web` and `spring-boot-starter-actuator` are both **`<optional>true</optional>`** — the starter contributes web/actuator integration (e.g. the `/actuator/license` endpoint) only if the consumer app already has those on the classpath, without forcing them.
- `spring-boot-configuration-processor` (`optional`) — generates IDE metadata for the starter's `@ConfigurationProperties`.
- Lombok (`provided`). Tests: `spring-boot-starter-test`, `spring-boot-starter-web` (test scope, to exercise the web integration), AssertJ.

**Gotcha:** because `spring-web`/actuator are optional, the auto-config must be written defensively (conditional on those classes being present). The sample app *does* bring `web`, `actuator`, and `security`, which is why it gets the full `/actuator/license` experience.

---

## 1.5 `sample-docker-app/pom.xml`

**Responsibility:** a reference Spring Boot consumer demonstrating the starter end-to-end. `<finalName>sample-docker-app</finalName>` → `target/sample-docker-app.jar` (matches its Dockerfile).

**Dependencies:** `web`, `actuator`, `security`, the **`license-verifier-spring-boot-starter`** (`${project.version}`), Lombok (`optional`), and `spring-boot-starter-test`.

**Build:** `spring-boot-maven-plugin` with Lombok excluded from the fat jar (same pattern as the API).

---

# 2. Container Build & Orchestration

## 2.1 `docker-compose.yml`

Defines five services across **two isolated bridge networks** and one named volume. There is no top-level `version:` key (modern Compose spec). Cross-cutting patterns: every service has `restart: unless-stopped`, CPU/memory `deploy.resources.limits`, and the data services have healthchecks that gate dependents.

### Network topology (security boundary)

```
backend  (bridge) ── postgres ── redis ── control-panel-api ── sample-docker-app
frontend (bridge) ── admin-ui
```

- **`backend`** carries everything that touches secrets/data. Neither `postgres` nor `redis` publishes a host port — they are reachable only as `postgres:5432` / `redis:6379` on this network. (The file documents the debug-only escape hatch: bind to `127.0.0.1` explicitly, never `0.0.0.0`.)
- **`frontend`** holds *only* `admin-ui`. This is deliberate: the admin-ui dev container runs `npm install` (arbitrary third-party install scripts) and must **not** be able to reach Postgres/Redis. It only needs to expose the Vite dev server to the host. There is no link between the two networks, so a compromised `npm install` cannot pivot to the database.

### Service: `postgres`

| Aspect | Value / Note |
|---|---|
| Image | `postgres:16.4` — **pinned patch tag**, not mutable `:16`, so a rebuild can't silently pull a different minor and the running image is reproducible. |
| Env | `POSTGRES_DB=cp`; `POSTGRES_USER=${POSTGRES_USER:-cp}` (defaults to `cp`); `POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?...}` (**required** — startup aborts if unset). |
| Ports | None published (network-only). |
| Volume | `pg-data:/var/lib/postgresql/data` (named volume; data survives recreation). |
| Healthcheck | `pg_isready -U $POSTGRES_USER -d cp`, interval 5s / timeout 5s / retries 10. |
| Limits | 1.0 CPU, 512M. |

### Service: `redis`

| Aspect | Value / Note |
|---|---|
| Image | `redis:7.4` — pinned patch tag. |
| Why authenticated | Redis backs **session-revocation (jti denylist)** and **brute-force/lockout counters**. If it ran unauthenticated on a shared net, any container could `FLUSHALL` to reset lockouts or revive logged-out tokens. So it starts with `--requirepass ${REDIS_PASSWORD:?...}` (**required**). |
| Env | `REDIS_PASSWORD` exposed inside the container so the healthcheck can `AUTH` at runtime. |
| Ports | None published. |
| Healthcheck | Authenticated ping: `redis-cli -a "$$REDIS_PASSWORD" --no-auth-warning ping | grep -q PONG`. The doubled `$$` escapes Compose interpolation so the **container shell** expands `$REDIS_PASSWORD` at runtime. AUTH must succeed for the container to report healthy. Interval 5s / timeout 5s / retries 10. |
| Limits | 0.5 CPU, 256M. |

### Service: `control-panel-api`

| Aspect | Value / Note |
|---|---|
| Build | `context: .`, `dockerfile: control-panel-api/Dockerfile` (build context is the repo root so the multi-module build can see sibling POMs). |
| depends_on | Waits for **both** `postgres` and `redis` to be `service_healthy` before starting — so Liquibase migrations never race an unready DB. |
| Ports | `8080:8080` (the only backend service published to the host; intended to sit behind a TLS proxy). |
| Limits | 2.0 CPU, 1024M. |

Environment (all `${VAR:?...}` ones are **required** — startup aborts if missing/empty):

| Var | Value | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/cp` | Points at the in-network Postgres. |
| `DB_USER` | `${POSTGRES_USER:-cp}` | Mirrors the postgres service. |
| `DB_PASS` | `${POSTGRES_PASSWORD:?...}` | **Required.** Same secret as Postgres. |
| `REDIS_URL` | `redis://redis:6379` | In-network Redis. |
| `REDIS_PASSWORD` | `${REDIS_PASSWORD:?...}` | **Required.** Same secret the redis service enforces; sent as `spring.data.redis.password`. |
| `TZ` | `UTC` | Container timezone. |
| `JAVA_TOOL_OPTIONS` | `-Duser.timezone=UTC` | Belt-and-suspenders JVM tz pin: some host JVMs resolve the legacy alias `Asia/Calcutta`, which PostgreSQL rejects on connect, aborting startup. |
| `APP_KEY_ENC_MASTER` | `${APP_KEY_ENC_MASTER:?...}` | **Required.** Base64 AES master key that envelope-encrypts signing private keys at rest. |
| `APP_AUTH_SESSION_SECRET` | `${APP_AUTH_SESSION_SECRET:?...}` | **Required, ≥32 chars.** Signs/derives session material. |
| `APP_CORS_ALLOWED_ORIGINS` | `${APP_CORS_ALLOWED_ORIGINS:-http://localhost:5173}` | Allowed SPA origin(s); defaults to the local Vite dev server. |

### Service: `sample-docker-app`

| Aspect | Value / Note |
|---|---|
| Build | `context: .`, `dockerfile: sample-docker-app/Dockerfile`. |
| Ports | `9090:9090`. |
| Limits | 0.5 CPU, 256M. |
| License mount | **Long-syntax bind of a single FILE** `./samples/license.lic → /etc/app/license.lic` (`read_only: true`, `bind.create_host_path: false`). |

> **Why the verbose mount matters (real bug it fixes).** The earlier short-syntax mount (`./samples/license.lic:/etc/app/license.lic`) made Docker **auto-create a directory** named `license.lic` on the host when the file was absent; that then mounts *as a directory* inside the container and breaks license loading with a confusing error. `create_host_path: false` makes the bind **fail fast** when the source file is missing. The `license.lic` must exist on the host first — generate one via the control panel's license issue/download flow (see `samples/README.md`).

### Service: `admin-ui`

| Aspect | Value / Note |
|---|---|
| Image | `node:20.18-alpine` — pinned patch tag (this is the *dev* container, not the nginx production image from `admin-ui/Dockerfile`). |
| working_dir | `/app`, with the source bind-mounted `./admin-ui:/app`. |
| command | `sh -c "npm install && npm run dev -- --host"` (Vite dev server, exposed to the host). |
| Network | **`frontend` only** (isolated — cannot reach the backend). |
| Ports | `5173:5173`. |
| Limits | 1.0 CPU, 512M. |

> **Important:** in this compose file `admin-ui` runs the **dev server**, while `admin-ui/Dockerfile` builds a **production nginx image**. Compose's dev container is for local iteration; the Dockerfile is what you ship. They are two different runtime modes of the same source.

### Volumes & networks

- `volumes: pg-data` — named, persistent Postgres data.
- `networks: backend`, `frontend` — both `driver: bridge`, no inter-network routing.

---

## 2.2 `control-panel-api/Dockerfile`

Two-stage build.

**Stage 1 — builder (`maven:3.9-eclipse-temurin-21`):**
1. Copies the parent POM and **all four** module POMs first (layer-caching dependency resolution), then `control-panel-api/src`.
2. `mvn -B -pl control-panel-api -am -DskipTests package` — builds the API **and** its required upstream modules (`-am` = also-make) but **skips tests** (tests run in CI, not in the image build).

**Stage 2 — runtime (`eclipse-temurin:21-jre-alpine`):**
- Creates a non-root `app` user/group (`addgroup -S` / `adduser -S`).
- Copies `control-panel-api/target/control-panel-api.jar → /app/app.jar` with `--chown=app:app`.
- `USER app` (runs unprivileged), `EXPOSE 8080`.
- **HEALTHCHECK:** `wget -qO- http://localhost:8080/actuator/health` with `--start-period=40s` (generous, because Liquibase migrations + Spring Boot startup take a moment), interval 30s / timeout 3s / retries 3.
- `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`.

> **Gotcha:** the builder copies only `control-panel-api/src` (not the other modules' `src`). The API module compiles against the *published/managed* coordinates of its siblings for runtime, and pulls `license-verifier` only at test scope — which is skipped here — so this works. If you ever need a sibling's source at compile time for the API, you'd have to add its `src` copy.

## 2.3 `sample-docker-app/Dockerfile`

Two-stage build with explicit BuildKit syntax (`# syntax=docker/dockerfile:1.7`).

**Stage 1 — builder (`maven:3.9-eclipse-temurin-21`, `WORKDIR /workspace`):**
1. Copies parent + module POMs (no `control-panel-api/pom.xml`, since the sample doesn't depend on the API).
2. `mvn -pl sample-docker-app -am -DskipTests dependency:go-offline || true` — a **best-effort** dependency-cache layer (the `|| true` tolerates the fact that sibling modules aren't built yet).
3. Copies the `src` of `license-verifier`, `license-verifier-spring-boot-starter`, and `sample-docker-app` (the sample needs the two siblings' source to build).
4. `mvn -pl sample-docker-app -am -DskipTests package`.

**Stage 2 — runtime (`eclipse-temurin:21-jre-alpine`):**
- Non-root `app` user, `WORKDIR /app`.
- `VOLUME ["/etc/app"]` — where customers mount their license file.
- Copies `sample-docker-app/target/sample-docker-app.jar → /app/app.jar`.
- **Baked-in default ENV (overridable):** `LICENSE_PATH=/etc/app/license.lic`, `LICENSE_AUDIENCE=docker-app-prod`, `LICENSE_ISSUER=https://control-panel.example.com`, `LICENSE_STRICT=true`, `JAVA_OPTS=""`.
- `USER app`, `EXPOSE 9090`.
- **HEALTHCHECK** on `:9090/actuator/health` (`--start-period=20s`).
- `ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]` — wrapped in `sh -c` so `$JAVA_OPTS` is expanded; `exec` makes the JVM PID 1 so it receives SIGTERM directly (clean shutdown).

> **Note:** the image defaults `LICENSE_STRICT=true` (fail hard on an invalid/missing license), whereas `application.yml` of the sample app defaults `app.license.strict=false`. The Dockerfile's ENV wins inside the container — production-leaning default for the shipped image.

## 2.4 `admin-ui/Dockerfile` (production SPA image)

Two-stage build producing a static nginx image.

**Stage 1 — build (`node:22-alpine`):**
- `npm ci || npm install` (reproducible install, fallback if no lockfile match).
- `ARG VITE_API_BASE=http://localhost:8080` → `ENV VITE_API_BASE` so Vite bakes the API base into the bundle at build time.
- `npm run build` → `/app/dist`.

**Stage 2 — runtime (`nginx:alpine`):**
- Copies `dist → /usr/share/nginx/html`.
- Copies `nginx.conf → /etc/nginx/templates/default.conf.template`. The official nginx entrypoint renders `*.template` through **envsubst** at container start, substituting `${CSP_CONNECT_SRC}` so the CSP `connect-src` API origin is configurable per deployment rather than hardcoded.
- `ARG VITE_API_BASE` + `ENV CSP_CONNECT_SRC=$VITE_API_BASE` — the CSP origin defaults to the build-time API base but can be overridden at run time.
- `EXPOSE 80`, `CMD ["nginx", "-g", "daemon off;"]`.

### `admin-ui/nginx.conf` (envsubst template)

Serves the SPA and applies security headers. Key behaviors:
- **HTTP→HTTPS:** `if ($http_x_forwarded_proto = "http") { return 301 https://... }` handles upstream TLS termination without a redirect loop. A commented in-container TLS terminator (443 server block + `ssl_certificate*`) is provided for self-contained TLS.
- **SPA routing:** `try_files $uri $uri/ /index.html`.
- **Security headers**, repeated in **both** the `/` and `/assets/` blocks: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Strict-Transport-Security`, and a CSP whose `connect-src` includes `'self' ${CSP_CONNECT_SRC}`.

> **Gotcha (documented in the file, finding P3):** nginx's `add_header` does **not** inherit into a `location` block that defines its own `add_header`. The headers are therefore deliberately duplicated in `/assets/`; do not "DRY" them away or `/assets/` responses will ship with no CSP / clickjacking protection.

> The `samples/` directory is git-tracked only via `.gitkeep` + `README.md`; `license.lic`/`jwks.json` you place there are local-only artifacts. `admin-ui/.env.example` documents the single front-end env var `VITE_API_BASE=http://localhost:8080`.

---

# 3. CI — `.github/workflows/ci.yml`

Triggers on `push` to `main`/`master` and on every `pull_request`. A `concurrency` group (`ci-${{ github.workflow }}-${{ github.ref }}` with `cancel-in-progress: true`) cancels superseded runs on the same ref. Top-level `permissions: contents: read` (least privilege for the `GITHUB_TOKEN`). All jobs run on `ubuntu-latest`. Four jobs:

### Job `build` — "Build & Test (mvn verify)"
1. Checkout (`actions/checkout@v4`).
2. Temurin JDK 21 (`actions/setup-java@v4`).
3. Cache `~/.m2/repository`, keyed by `${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}` (re-keys when any POM changes) with a `${{ runner.os }}-maven-` restore fallback.
4. **`mvn -B verify`** — compiles, runs Surefire unit tests **and** Failsafe integration tests. Testcontainers (Postgres via the `jdbc:tc` URL) works out of the box because Docker is preinstalled and running on GitHub-hosted runners.
5. Always (`if: always()`) uploads JaCoCo reports (`**/target/site/jacoco/`) as the `jacoco-coverage` artifact (report-only; no enforced threshold).

### Job `dependency-updates` — "Dependency updates (non-blocking)"
`continue-on-error: true` (never fails CI). Checkout + JDK 21 + Maven cache, then `mvn -B versions:display-dependency-updates` — a lightweight, informational "what's newer" report.

### Job `dependency-check` — "Vulnerability scan (OWASP + npm audit)" — **the real gate**
This is the **CVE gate**. Steps: checkout, JDK 21, Maven cache, plus a dedicated cache of the OWASP NVD data feed (`~/.m2/repository/org/owasp/dependency-check-data`, key `${{ runner.os }}-owasp-nvd`) so the full CVE DB isn't re-downloaded every run. Then:

```
mvn -B org.owasp:dependency-check-maven:check \
  -Dformats=HTML,JSON \
  -DfailBuildOnCVSS=7 \
  ${NVD_API_KEY:+-DnvdApiKey=$NVD_API_KEY}
```

- **`-DfailBuildOnCVSS=7`** — fails the build on any dependency with a CVSS ≥ 7 (High/Critical). The inline comment flags a real prior bug: the previous threshold was **11**, which is *impossible* (CVSS maxes at 10), so the gate could never fire. `7` makes it real.
- **`NVD_API_KEY`** (repo secret) is passed only if present (`${VAR:+...}` bash idiom). Without it the scan still runs, just slower / flakier.
- The report (`**/target/dependency-check-report.*`) is uploaded `if: always()` as `owasp-dependency-check`.
- A trailing **`npm audit --audit-level=high`** in `admin-ui/` runs on Node 20 but is **`continue-on-error: true`** (informational only — the prod bundle is clean; known advisories are dev-only Vite/esbuild issues).

> **Operator note:** the OWASP gate is the one that can block a merge for a transitive CVE. The parent POM's SCA overrides (json-smart, BouncyCastle, pgjdbc 42.7.7) exist precisely to keep this gate green — when it flags something, the fix is usually a new override property in the parent `pom.xml` rather than a code change.

### Job `admin-ui` — "Admin UI (install, typecheck, build)"
Checkout + Node 20 with `cache: npm` keyed on `admin-ui/package-lock.json`. Then in `admin-ui/`:
- `npm ci` (reproducible install from the committed lockfile).
- `npm run lint` → which is `tsc -b --noEmit` (type-check the whole project; **type/contract drift fails CI**).
- `npm run build` → `tsc -b && vite build` (catches build-time regressions).

> The `package.json` scripts confirm: `lint` is a no-emit `tsc`, `build` is `tsc -b && vite build`, `test` is `vitest run` (CI does not currently run the Vitest suite).

---

# 4. Dependency Automation — `.github/dependabot.yml`

`version: 2`. Three ecosystems, all on a **weekly Monday 06:00** cadence with `commit-message` prefixes and labels.

### 4.1 Maven (`directory: "/"`, the root reactor)
- `open-pull-requests-limit: 10`, labels `dependencies` + `java`, prefix `deps(maven)`.
- **Group `spring`**: batches `org.springframework*` and `org.springframework.boot:*` into one PR so the managed BOM stays internally consistent.
- **Ignore:** major *and* minor updates of `org.springframework.boot:spring-boot-starter-parent` — stay on 3.3.x; the move to 3.4/3.5 is a tracked out-of-band migration (also moves SAML off EOL OpenSAML 4). Patch bumps of the parent are still allowed.

### 4.2 npm (`directory: "/admin-ui"`)
- Limit 10, labels `dependencies` + `javascript`, prefix `deps(npm)`.
- **Group `react`**: keeps `react`, `react-dom`, `@types/react`, `@types/react-dom` moving together.

### 4.3 GitHub Actions (`directory: "/"`)
- Limit 5, labels `dependencies` + `github-actions`, prefix `deps(actions)`. Keeps `actions/*` pinned versions patched.

---

# 5. Runtime Configuration (control-panel-api)

Logging is configured by `logback-spring.xml` (auto-detected; no `logging.config` needed). Profiles select datasource defaults, security relaxations, and the log appender. There are three profile files:

- **`application.yml`** — the base / **prod-safe** defaults. Every production secret is a *required* env var (`${VAR:?message}`) with **no baked-in fallback**.
- **`application-dev.yml`** — local-dev relaxations (`spring.profiles.active=dev`). **Must never be active in prod.**
- **`application-test.yml`** — the `@ActiveProfiles("test")` profile: Testcontainers Postgres, no Redis, fixed test secrets.

> A `target/classes/application-dev.yml` also exists — that is a build artifact (compiled copy), not a separate source.

## 5.1 `application.yml` (base / prod)

### `spring.*`
- `application.name: control-panel` — also tags Micrometer metrics.
- `lifecycle.timeout-per-shutdown-phase: 30s` — graceful shutdown window; paired with `server.shutdown: graceful`. On SIGTERM, stop accepting new requests and drain in-flight ones (bounded by this timeout) instead of cutting connections.
- `datasource.url/username/password` — all **required** via `${DB_URL:?...}` / `${DB_USER:?...}` / `${DB_PASS:?...}`. No baked-in `cp/cp` fallback (a packaged jar with well-known creds is a prod hazard). Dev/test profiles supply their own locals.
- `jpa.hibernate.ddl-auto: validate` — Liquibase owns the schema; Hibernate only validates it matches the entities. `show-sql: false`. Dialect `PostgreSQLDialect`.
- `liquibase.change-log: classpath:db/changelog/db.changelog-master.yaml`.
- `data.redis.url: ${REDIS_URL:redis://localhost:6379}`, `password: ${REDIS_PASSWORD:}` (empty default → no AUTH, only valid against a passwordless local Redis), `timeout: ${REDIS_TIMEOUT:2s}`. The short timeout is deliberate: the jti-denylist check is on the hot path of every authenticated request, so a Redis stall must not block requests for Lettuce's default 60s.
- `servlet.multipart.max-file-size / max-request-size: 1MB` — caps upload size.

### `server.*`
- `port: 8080`.
- **`forward-headers-strategy: framework`** — installs Spring's `ForwardedHeaderFilter` to honor `X-Forwarded-*` from the TLS-terminating proxy. Without this, `request.isSecure()` is false on every plain-HTTP proxy→app hop, so Spring Security never emits HSTS and scheme-derived URLs resolve as `http`. **Operational requirement:** the fronting proxy MUST set `X-Forwarded-Proto`/`X-Forwarded-For` and MUST strip any client-supplied copies (the app now trusts them). Pair with `app.audit.trusted-proxies` so the audit log's client-IP view matches.
- `shutdown: graceful`, `max-http-request-header-size: 16KB`, `tomcat.max-http-form-post-size: 256KB`, `tomcat.max-swallow-size: 256KB`.

### `management.*` (Actuator)
- **Exposed web endpoints:** `health, info, prometheus` only. The raw `/actuator/metrics` tree is intentionally *not* web-exposed (Prometheus scrapes `/actuator/prometheus`). Non-health endpoints remain secured by `SecurityConfig`; the minimal exposure is defense-in-depth if that layer regresses.
- `endpoint.health.show-details: never`; `probes.enabled: true` → Kubernetes-style `/actuator/health/liveness` and `/readiness` groups; `health.livenessstate.enabled` + `readinessstate.enabled: true`.
- `metrics.tags.application: control-panel` — tags all meters so a shared registry can separate services.
- `prometheus.metrics.export.enabled: true`.

### `springdoc.*`
- `api-docs.enabled: false`, `swagger-ui.enabled: false` (path `/swagger`). **Disabled in prod** — handing an anonymous client the full enumerated API surface is an information-disclosure aid. The dev profile re-enables both.

### `app.*` (application-specific)

| Key | Default | Purpose |
|---|---|---|
| `auth.session-secret` | `${APP_AUTH_SESSION_SECRET:}` | Session signing secret (≥32 chars; compose makes it required). |
| `auth.session-ttl` | `PT30M` | Session lifetime. |
| `auth.revocation.enabled` | `true` | Enable session/jti revocation. |
| `auth.expose-reset-token` | `${APP_AUTH_EXPOSE_RESET_TOKEN:false}` | **Prod must stay false**; only dev flips it true. |
| `auth.lockout.max-attempts` | `5` | Failed logins per account/window before lock. |
| `auth.lockout.window` | `PT5M` | Sliding count window. |
| `auth.lockout.lockout` | `PT15M` | Lock duration. |
| `auth.lockout.per-ip-max` | `20` | Per-IP failed-attempt ceiling (anti-spray). Counters are Redis-backed (cluster-safe) when a `RedisConnectionFactory` is present, else in-memory. |
| `mfa.enabled` | `true` | Master TOTP MFA switch. |
| `cors.allowed-origins` | `${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}` | Allowed SPA origins. |
| `ratelimit.auth.capacity` / `refill-per-minute` | `10` / `10` | Custom in-memory auth rate limiter (bucket4j core). |
| `signing.master-key` | `${APP_KEY_ENC_MASTER:}` | **Legacy single AES KEK**; KeyEncryptor keeps it under reserved KEK id `default` for back-compat decryption. |
| `signing.active-master-key-id` | `${APP_SIGNING_ACTIVE_MASTER_KEY_ID:v1}` | Which KEK encrypts *new* blobs. |
| `signing.master-keys` | `${APP_SIGNING_MASTER_KEYS:v1:${APP_KEY_ENC_MASTER:}}` | Versioned KEK list — **a single STRING** of comma/space-separated `id:base64key` entries (not a YAML map). Default maps `v1`→`APP_KEY_ENC_MASTER`, so an undecorated deploy behaves exactly as before this property existed. KEK rotation = add a new id, repoint `active-master-key-id`, then re-encrypt rows via `KeyService.rotateKek`. |
| `signing.issuer` | `${APP_ISSUER:https://control-panel.example.com}` | `iss` claim on issued licenses. |
| `signing.default-audience` | `${APP_DEFAULT_AUDIENCE:docker-app-prod}` | Default `aud`. |
| `signing.crl-ttl` | `${APP_CRL_TTL:PT1H}` | Signed-CRL cache lifetime. |
| `usage.occurred-at-max-past` / `max-future` | `P35D` / `PT5M` | Accepted clock window for usage events. |
| `usage.enforce-limit` | `true` | Enforce usage/seat limits. |
| `audit.trusted-proxies` | `${APP_AUDIT_TRUSTED_PROXIES:}` | CIDRs whose `X-Forwarded-For` is trusted; **empty ⇒ never trust XFF**. |
| `audit.fail-closed-actions` | list | Sensitive actions (license issue/revoke, key rotate, RBAC role changes, owner transfer, API-key create/revoke, SSO provider create/delete) where an audit-write failure must **fail closed** (abort the operation). |
| `billing.currency` | `${APP_BILLING_CURRENCY:USD}` | ISO-4217 default currency. |
| `billing.default-unit-amount` | `${APP_BILLING_DEFAULT_UNIT_AMOUNT:0}` | Default per-unit price in minor units; `0` = entitlement-only (track usage, bill nothing). |
| `licenses.default-ttl-days` | `365` | Default license validity. |
| `licensing.lease-window` | `${APP_LICENSING_LEASE_WINDOW:PT24H}` | A node that heartbeats within this window counts as an active seat. |
| `licensing.expiry-warning` | `${APP_LICENSING_EXPIRY_WARNING:P14D}` | How early the sweeper emits a `license.expiring` outbox event. |
| `webhooks.max-attempts` | `${APP_WEBHOOKS_MAX_ATTEMPTS:8}` | Delivery attempts before a delivery is parked FAILED (capped exponential backoff). |
| `webhooks.timeout` | `${APP_WEBHOOKS_TIMEOUT:PT10S}` | Per-attempt connect+read timeout. |
| `idempotency.ttl` | `${APP_IDEMPOTENCY_TTL:P1D}` | TTL for stored `Idempotency-Key` records (retries within replay the original response). |
| `sso.enabled` | `${APP_SSO_ENABLED:true}` | SAML SSO master switch. |
| `sso.base-url` | `${APP_SSO_BASE_URL:http://localhost:8080}` | SP base URL for SAML ACS/metadata. |
| `ui.base-url` | `${APP_UI_BASE_URL:http://localhost:5173}` | Admin SPA base (links/redirects). |
| `events.listener.enabled` | `${APP_EVENTS_LISTENER_ENABLED:false}` | Outbox/NOTIFY listener (off by default). |
| `events.listener.poll-ms` | `1000` | Poll interval. |
| `observability.access-log.enabled` | `${APP_ACCESS_LOG_ENABLED:true}` | Structured HTTP access log (`AccessLogFilter`; health-probe traffic excluded). |
| `observability.tracing.enabled` | `${APP_TRACING_ENABLED:true}` | Correlation-id propagation (`CorrelationIdFilter` binds the `requestId` MDC key surfaced by both log encoders). |

### `bucket4j.enabled: false`
Disables the bucket4j starter auto-config (would fail at startup with `NoCacheConfiguredException`); rate limiting is the custom `RateLimitFilter`.

### `logging.level`
`org.springframework.security: INFO` (also mirrored in `logback-spring.xml`).

## 5.2 `application-dev.yml` (`spring.profiles.active=dev`) — **never use in prod**

A bold warning header: this file intentionally relaxes security for local dev. The base `application.yml` keeps every value at its safe default, so accidentally *packaging* this file is harmless **unless** the dev profile is explicitly activated.

- **Datasource:** restores localhost fallbacks `jdbc:postgresql://localhost:5432/cp`, `cp`/`cp` (still env-overridable).
- **springdoc:** re-enables `api-docs` and `swagger-ui`.
- **`app.dev.bootstrap-admin`** (consumed by `com.example.cp.auth.DevBootstrapAdmin`, `@Profile("dev")`): on startup, *if the users table is empty*, seeds an ACTIVE org (slug `keyforge-dev`), a super-admin user, and an OWNER membership; logs the credentials at WARN. Idempotent (only runs at zero users), never touches the test profile, adds no migration. Overridable via `APP_BOOTSTRAP_ADMIN_EMAIL` (default `admin@example.com`), `APP_BOOTSTRAP_ADMIN_PASSWORD` (default `Admin123!ChangeMe`), `APP_BOOTSTRAP_ORG_NAME` (default `Keyforge (dev)`); set `enabled: false` to disable.
- `auth.expose-reset-token: true` — return the raw reset token in API responses (no mail server locally).
- `cors.allowed-origins`: permissive (`localhost:5173`, `127.0.0.1:5173`, `localhost:3000`).
- `management.endpoint.health.show-details: always`.
- `logging.level.com.example: DEBUG`. The `dev` profile also makes `logback-spring.xml` select the plain console appender.

## 5.3 `application-test.yml` (`@ActiveProfiles("test")`)

- **Excludes `RedisAutoConfiguration`** so no `RedisConnectionFactory` exists → `SessionRevocationConfig` falls back to the in-memory store, letting session-revocation ITs run against just a Testcontainers Postgres (no Redis service).
- **Datasource:** `${DB_URL:jdbc:tc:postgresql:16:///cp_test}` with `driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver` — the `jdbc:tc:` URL spins up an ephemeral Postgres 16 container per test run.
- `jpa.ddl-auto: validate`; Liquibase runs the same master changelog.
- **Fixed test secrets** (base64, clearly non-production): `auth.session-secret`, `signing.master-key`, and `signing.master-keys: v1:<...>` with `active-master-key-id: v1`. `expose-reset-token: false`. Issuer `https://test-control-panel.example.com`, audience `docker-app-test`. Billing USD / 0; `licenses.default-ttl-days: 365`.

## 5.4 `logback-spring.xml`

Auto-detected (name ends in `-spring.xml`, enabling `<springProfile>` tags). Includes Spring Boot's `defaults.xml`. Two appenders, profile-selected:

- **`CONSOLE_PLAIN`** (`PatternLayoutEncoder`) — Spring Boot's default pattern **plus `[%X{requestId:--}]`** so developers see the correlation id on every line (falls back to `-`).
- **`CONSOLE_JSON`** (`net.logstash.logback.encoder.LogstashEncoder`) — single-line JSON for log shippers/SIEM. Emits all MDC entries (incl. `requestId` from `CorrelationIdFilter`) as top-level fields, plus static `{"app":"control-panel","service":"control-panel-api"}`; remaps `timestamp`→`@timestamp` and ignores the encoder's `version`/`levelValue` fields.

Profile→appender mapping:

| Active profile(s) | Root appender |
|---|---|
| `default` \| `dev` \| `test` | `CONSOLE_PLAIN` (human-readable) |
| `json` \| `prod` | `CONSOLE_JSON` |
| any other / unnamed combo | `CONSOLE_JSON` (prod-safe catch-all) |

Root level `INFO`; `org.springframework.security` pinned to `INFO`. The catch-all `<springProfile name="!default &amp; !dev &amp; !test &amp; !json &amp; !prod">` guarantees exactly one root appender is always bound (defaulting to JSON, the safe choice).

> **Operator takeaway:** to get JSON logs in production, run with `SPRING_PROFILES_ACTIVE=prod` (or `json`). With **no** profile set you get the plain-text `default` appender — so explicitly set the profile in real deployments, or rely on the JSON catch-all only if you set some other profile name.

## 5.5 `sample-docker-app/src/main/resources/application.yml`

The reference consumer's config (drives the `license-verifier` starter).

- `server.port: 9090`; `spring.application.name: sample-docker-app`.
- **Actuator HTTP Basic** (`spring.security.user`): `ACTUATOR_USER` (default `actuator`) / `ACTUATOR_PASSWORD` (default `changeit`), role `ACTUATOR`. `/actuator/license` and detailed `/actuator/health` are auth-gated by the app's `SecurityConfig`; basic health/info probes stay open for orchestration.
- **`app.license.*`** (the starter's binding):

  | Key | Default | Purpose |
  |---|---|---|
  | `path` | `${LICENSE_PATH:/etc/app/license.lic}` | The mounted `.lic` file. |
  | `audience` | `${LICENSE_AUDIENCE:docker-app-prod}` | Expected `aud`. |
  | `issuer` | `${LICENSE_ISSUER:https://control-panel.example.com}` | Expected `iss`. |
  | `refresh-from-url` | `${LICENSE_JWKS_URL:}` | Optional JWKS URL to refresh public keys; blank = use bundled JWKS. |
  | `refresh-interval` | `${LICENSE_REFRESH_INTERVAL:PT24H}` | JWKS refresh cadence. |
  | `clock-skew` | `${LICENSE_CLOCK_SKEW:PT5M}` | Allowed exp/nbf skew. |
  | `read-only-on-expiry` | `${LICENSE_READ_ONLY_ON_EXPIRY:true}` | On expiry, degrade to read-only rather than hard-fail. |
  | `strict` | `${LICENSE_STRICT:false}` | Strict verification mode (the Docker image overrides this to `true`). |
  | `crl-url` | `${LICENSE_CRL_URL:}` | Signed CRL (`typ=crl+jwt`) URL; blank disables revocation checking (`RevocationChecker.none()`). |

- **Actuator exposure:** `health, info, license`; `health.show-details: when-authorized`; the custom `license` endpoint enabled.
- **Logging:** `com.example.licenseverifier` and `com.example.sample` at `INFO`.

---

# 6. Complete Environment-Variable Reference

> "Required?" = **Yes** means startup/`docker compose up` aborts if unset (`${VAR:?...}` in compose, or `${VAR:?...}` in `application.yml`). Defaults shown are the value used when the var is absent.

## 6.1 control-panel-api

| Variable | Purpose | Required? | Default |
|---|---|---|---|
| `DB_URL` | JDBC URL for Postgres | **Yes** (prod) | dev: `jdbc:postgresql://localhost:5432/cp`; test: `jdbc:tc:postgresql:16:///cp_test` |
| `DB_USER` | DB username | **Yes** (prod) | dev: `cp`; test: `test` |
| `DB_PASS` | DB password | **Yes** (prod) | dev: `cp`; test: `test` |
| `REDIS_URL` | Redis URL (denylist + lockout counters) | No | `redis://localhost:6379` |
| `REDIS_PASSWORD` | Redis AUTH password | **Yes** in compose; optional for the app | empty (no AUTH) |
| `REDIS_TIMEOUT` | Redis command timeout | No | `2s` |
| `APP_KEY_ENC_MASTER` | Base64 AES KEK encrypting signing keys at rest | **Yes** | — |
| `APP_SIGNING_ACTIVE_MASTER_KEY_ID` | KEK id used to encrypt new blobs | No | `v1` |
| `APP_SIGNING_MASTER_KEYS` | `id:base64` KEK list (single string) | No | `v1:${APP_KEY_ENC_MASTER}` |
| `APP_AUTH_SESSION_SECRET` | Session secret (≥32 chars) | **Yes** | — |
| `APP_AUTH_EXPOSE_RESET_TOKEN` | Return raw reset token in API | No | `false` (dev: `true`) |
| `APP_AUTH_LOCKOUT_MAX_ATTEMPTS` | Failed logins/account before lock | No | `5` |
| `APP_AUTH_LOCKOUT_WINDOW` | Failed-attempt window | No | `PT5M` |
| `APP_AUTH_LOCKOUT_DURATION` | Lock duration | No | `PT15M` |
| `APP_AUTH_LOCKOUT_PER_IP_MAX` | Per-IP failed-attempt ceiling | No | `20` |
| `APP_MFA_ENABLED` | TOTP MFA master switch | No | `true` |
| `APP_CORS_ALLOWED_ORIGINS` | Allowed SPA origins (CSV) | No | `http://localhost:5173` |
| `APP_RATELIMIT_AUTH_CAPACITY` | Auth rate-limit bucket size | No | `10` |
| `APP_RATELIMIT_AUTH_REFILL` | Auth rate-limit refill/min | No | `10` |
| `APP_ISSUER` | License `iss` claim | No | `https://control-panel.example.com` |
| `APP_DEFAULT_AUDIENCE` | License default `aud` | No | `docker-app-prod` |
| `APP_CRL_TTL` | Signed-CRL cache TTL | No | `PT1H` |
| `APP_AUDIT_TRUSTED_PROXIES` | CIDRs whose XFF is trusted | No | empty (never trust XFF) |
| `APP_BILLING_CURRENCY` | Default ISO-4217 currency | No | `USD` |
| `APP_BILLING_DEFAULT_UNIT_AMOUNT` | Default unit price (minor units) | No | `0` |
| `APP_LICENSING_LEASE_WINDOW` | Active-seat heartbeat window | No | `PT24H` |
| `APP_LICENSING_EXPIRY_WARNING` | Expiry-warning lead time | No | `P14D` |
| `APP_WEBHOOKS_MAX_ATTEMPTS` | Webhook delivery attempts | No | `8` |
| `APP_WEBHOOKS_TIMEOUT` | Webhook per-attempt timeout | No | `PT10S` |
| `APP_IDEMPOTENCY_TTL` | Idempotency-Key record TTL | No | `P1D` |
| `APP_SSO_ENABLED` | SAML SSO master switch | No | `true` |
| `APP_SSO_BASE_URL` | SP base URL | No | `http://localhost:8080` |
| `APP_UI_BASE_URL` | Admin SPA base URL | No | `http://localhost:5173` |
| `APP_EVENTS_LISTENER_ENABLED` | Outbox/NOTIFY listener | No | `false` |
| `APP_ACCESS_LOG_ENABLED` | Structured HTTP access log | No | `true` |
| `APP_TRACING_ENABLED` | Correlation-id propagation | No | `true` |
| `TZ` / `JAVA_TOOL_OPTIONS` | Pin timezone to UTC (avoid pgjdbc tz reject) | recommended | `UTC` / `-Duser.timezone=UTC` (set in compose) |
| `SPRING_PROFILES_ACTIVE` | Active profile(s) (`prod`/`json` ⇒ JSON logs) | recommended | none (→ plain logs) |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Dev seed admin email (dev profile only) | No | `admin@example.com` |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | Dev seed admin password | No | `Admin123!ChangeMe` |
| `APP_BOOTSTRAP_ORG_NAME` | Dev seed org name | No | `Keyforge (dev)` |

## 6.2 docker-compose (Postgres/Redis services)

| Variable | Purpose | Required? | Default |
|---|---|---|---|
| `POSTGRES_USER` | Postgres role (and `DB_USER`) | No | `cp` |
| `POSTGRES_PASSWORD` | Postgres password (and `DB_PASS`) | **Yes** | — |
| `REDIS_PASSWORD` | Redis `--requirepass` (and app AUTH) | **Yes** | — |
| `APP_KEY_ENC_MASTER`, `APP_AUTH_SESSION_SECRET`, `APP_CORS_ALLOWED_ORIGINS` | Passed through to the API (see §6.1) | first two **Yes** | — / — / `http://localhost:5173` |

## 6.3 sample-docker-app

| Variable | Purpose | Required? | Default (yml) | Default (Docker image ENV) |
|---|---|---|---|---|
| `LICENSE_PATH` | Path to `.lic` file | No | `/etc/app/license.lic` | `/etc/app/license.lic` |
| `LICENSE_AUDIENCE` | Expected `aud` | No | `docker-app-prod` | `docker-app-prod` |
| `LICENSE_ISSUER` | Expected `iss` | No | `https://control-panel.example.com` | `https://control-panel.example.com` |
| `LICENSE_JWKS_URL` | JWKS refresh URL | No | empty (use bundled) | — |
| `LICENSE_REFRESH_INTERVAL` | JWKS refresh cadence | No | `PT24H` | — |
| `LICENSE_CLOCK_SKEW` | Allowed exp/nbf skew | No | `PT5M` | — |
| `LICENSE_READ_ONLY_ON_EXPIRY` | Degrade to read-only on expiry | No | `true` | — |
| `LICENSE_STRICT` | Strict verification | No | `false` | **`true`** (image overrides) |
| `LICENSE_CRL_URL` | Signed CRL URL (blank disables revocation) | No | empty | — |
| `ACTUATOR_USER` / `ACTUATOR_PASSWORD` | Actuator HTTP Basic | No (change in prod) | `actuator` / `changeit` | — |
| `JAVA_OPTS` | Extra JVM flags | No | — | empty |

## 6.4 admin-ui (build/runtime)

| Variable | Purpose | Required? | Default |
|---|---|---|---|
| `VITE_API_BASE` | API base URL baked into the SPA bundle (build-time `ARG`/`ENV`) | No | `http://localhost:8080` |
| `CSP_CONNECT_SRC` | CSP `connect-src` API origin (envsubst at container start) | No | `$VITE_API_BASE` |

## 6.5 CI secret

| Variable | Purpose | Required? | Default |
|---|---|---|---|
| `NVD_API_KEY` (repo secret) | Speeds/de-flakes the OWASP NVD feed in the CVE gate | No (scan still runs, slower) | unset |

---

# 7. Operator quick-reference & gotchas

- **Bring the stack up:** set `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `APP_KEY_ENC_MASTER`, `APP_AUTH_SESSION_SECRET` (≥32 chars), then `docker compose up`. Missing any of these aborts the affected service immediately (by design).
- **Logs in prod:** set `SPRING_PROFILES_ACTIVE=prod` (or `json`) to get JSON logs; with no profile you get plain text.
- **Behind a proxy:** terminate TLS upstream, set `X-Forwarded-Proto`/`-For`, strip client copies, and set `APP_AUDIT_TRUSTED_PROXIES` to the proxy CIDR — otherwise HSTS won't emit and audit IPs will be wrong.
- **Sample app license mount:** create `samples/license.lic` *before* `up`, or the `create_host_path: false` bind fails fast (intended).
- **Never activate the `dev` profile in prod** — it seeds a super-admin and exposes reset tokens.
- **CVE gate (`mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7`)** is a real merge blocker; fix transitive CVEs by adding an override property in the parent `pom.xml` (precedent: pgjdbc 42.7.7, json-smart 2.5.2, BouncyCastle 1.78.1).
- **Tink is mandatory** for Ed25519 wherever Nimbus signs/verifies even though Nimbus marks it optional — don't drop it.
- **JaCoCo + Surefire `argLine`**: never add `<argLine>` to Surefire; it clobbers the JaCoCo agent injection (use `systemPropertyVariables`).
- **bucket4j starter stays disabled** (`bucket4j.enabled: false`); the app's rate limiting is custom.
