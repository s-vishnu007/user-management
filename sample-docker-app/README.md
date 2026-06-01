# sample-docker-app

A reference Spring Boot service that consumes the `license-verifier-spring-boot-starter` to enforce license entitlements at runtime. Use it as both an integration smoke-test for the control panel and as a copy-pasteable template for your own Dockerized product.

## Endpoints

| Method | Path | Permission required | Notes |
|---|---|---|---|
| GET | `/api/free` | none | Returns `{ "message": "ok", "plan": "<plan or null>" }`. |
| GET | `/api/license/status` | none | Snapshot of the loaded license: `status`, `plan`, `expiresAt`, `permissions`, `features`. |
| GET | `/api/export/pdf` | `export.pdf` | Returns a stub PDF. 403 if the license does not grant `export.pdf`. |
| GET | `/api/v2/data` | `api.v2` | Returns sample JSON. 403 without `api.v2`. |
| POST | `/api/admin/users/invite` | `admin.users.invite` | Body: `{ "email": "..." }`. Returns 202. |
| GET | `/actuator/health` | none | Spring Boot health probe. |
| GET | `/actuator/license` | none | License metadata exposed by the starter. |

## Running locally

```bash
# from the repo root (multi-module Maven project):
mvn -pl sample-docker-app -am package

LICENSE_PATH=./samples/license.lic \
LICENSE_STRICT=false \
java -jar sample-docker-app/target/sample-docker-app.jar
```

Without a license file, the app starts in non-strict mode and the protected endpoints return 403. Set `LICENSE_STRICT=true` to fail boot when no valid license is present.

## Running in Docker

```bash
docker build -f sample-docker-app/Dockerfile -t sample-docker-app:dev .

docker run --rm \
  -p 9090:9090 \
  -v "$(pwd)/samples:/etc/app:ro" \
  -e LICENSE_PATH=/etc/app/license.lic \
  -e LICENSE_AUDIENCE=docker-app-prod \
  -e LICENSE_ISSUER=https://control-panel.example.com \
  -e LICENSE_STRICT=true \
  sample-docker-app:dev
```

Mount your downloaded `.lic` file at `/etc/app/license.lic` (or set `LICENSE_PATH` to a different in-container path).

## Configuration

All settings are read from `application.yml` and overridable via environment variables.

| Env var | Default | Meaning |
|---|---|---|
| `LICENSE_PATH` | `/etc/app/license.lic` | Path to the `.lic` envelope. |
| `LICENSE_AUDIENCE` | `docker-app-prod` | Required `aud` claim. |
| `LICENSE_ISSUER` | `https://control-panel.example.com` | Expected `iss` claim. |
| `LICENSE_JWKS_URL` | _(empty)_ | Optional URL to refresh the JWKS from at runtime. When empty, the bundled `classpath:/jwks.json` is used. |
| `LICENSE_REFRESH_INTERVAL` | `PT24H` | ISO-8601 duration. Frequency for re-reading the license + refreshing the JWKS. |
| `LICENSE_CLOCK_SKEW` | `PT5M` | ISO-8601 duration. Tolerance for `exp` / `nbf` checks. |
| `LICENSE_READ_ONLY_ON_EXPIRY` | `true` | If `true`, an expired license keeps the app running with `status=READ_ONLY`. |
| `LICENSE_STRICT` | `false` | If `true`, the app refuses to start without a valid license. |

## Replacing the bundled JWKS

`src/main/resources/jwks.json` ships empty. Before deploying you must replace it with the control panel's public JWKS (download from `/.well-known/jwks.json` on your control plane), or configure `LICENSE_JWKS_URL` to fetch it dynamically. The verifier picks the right key by `kid` from the JWT header.

## Smoke-testing the integration

1. In the control panel, create an org and a Pro subscription.
2. Click "Issue License" and save the file as `samples/license.lic`.
3. `docker run` this image with `samples` mounted at `/etc/app`.
4. `curl localhost:9090/api/license/status` -> expect `status: ACTIVE`, the plan, and the permissions array.
5. `curl localhost:9090/api/export/pdf -o out.pdf` -> 200 if `export.pdf` is granted, 403 otherwise.
