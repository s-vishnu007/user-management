# User Management Control Panel

A control-panel + JWT licensing system for Dockerized products. Admins provision customer subscriptions; the system issues signed Ed25519 license JWTs (`.lic` files) that customer Docker apps verify offline using the bundled `license-verifier` SDK.

## What it is

- **control-panel-api** — Spring Boot 3.3 / Java 21 service: orgs, users, RBAC, plans, subscriptions, license issuance, JWKS, audit log.
- **admin-ui** — React + Vite admin console.
- **license-verifier / license-verifier-spring-boot-starter** — the SDK customers bundle into their Docker app to verify and enforce license entitlements (`@RequiresPermission`).
- **sample-docker-app** — reference integration showing how a customer wires the starter.

## Run locally

```bash
export APP_KEY_ENC_MASTER="$(openssl rand -base64 32)"
docker compose up --build
```

Services:
- Control-panel API: http://localhost:8080 (Swagger UI at `/swagger`)
- Admin UI (dev server): http://localhost:5173
- Sample Docker app: http://localhost:9090
- Postgres: localhost:5432 (db `cp`, user `cp`, pass `cp`)
- Redis: localhost:6379

## Where the SDK lives

`license-verifier/` is a plain-Java JAR with no Spring dependency.
`license-verifier-spring-boot-starter/` adds auto-config, the `@RequiresPermission` aspect, and a `/actuator/license` endpoint. See `docs/integration-guide.md` for customer integration.
