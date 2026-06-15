# `admin-ui/src/lib` — Data, Auth & Query Layer

## Module overview

This is the **presentation-free core** of the Aurora Glass admin SPA: the layer that sits between
React components and the control-panel REST API. Nothing here renders UI. It owns the single shared
**axios client** (`api.ts`), the **typed DTO/domain contract** that mirrors the backend (`types.ts`),
the **cookie-based authentication context** (`auth.tsx`), the **TanStack Query** client and its
global cache/retry policy (`queryClient.ts`), the browser **blob-download helper** (`download.ts`),
and the Vite environment typing (`vite-env.d.ts`).

The central design decision — and the thing that makes this whole layer simpler than a typical SPA —
is that **the SPA never holds a token in JavaScript.** Authentication rides entirely on the backend's
`HttpOnly` `cp_session` cookie. The browser attaches that cookie automatically on every request
(`withCredentials: true`), so there is no Bearer header to build, no `localStorage` to read, no token
refresh dance in the client. The SPA's only job is to (a) trigger the login that *causes* the cookie
to be set, and (b) ask `/auth/me` "who am I?" to bootstrap identity. This is the deliberate mitigation
for XSS token theft (findings **P1-16 / P2 #32**): an attacker who runs JS in the page still cannot
read the session, because it lives in a cookie JS cannot see.

### How it fits the bigger picture

The backend (`com.example.cp.auth`, documented in `docs/backend/auth.md`) issues the `cp_session`
cookie on password login, MFA step-2, and SSO success, then authenticates every protected request via
`JwtAuthFilter` reading that cookie. This `lib` layer is the **browser-side counterpart** of that
contract. The wiring is established once in `main.tsx`:

```
<QueryClientProvider client={queryClient}>     ← queryClient.ts
  <ToastProvider>
    <AuthProvider>                              ← auth.tsx (bootstraps /auth/me from the cookie)
      <MotionConfig><App/></MotionConfig>
```

So at app start: `AuthProvider` mounts, calls `auth.me()` through the shared axios client, and the
result determines whether the user sees the login page or the authenticated shell. Every page below
that uses TanStack Query hooks whose `queryFn` is one of the `api.ts` endpoint wrappers, and whose
errors are formatted by `apiErrorMessage` and surfaced via the toast layer. The `types.ts` interfaces
are the shared vocabulary across all of it.

Out of scope here (documented elsewhere): `toast.tsx`, `cn.ts`, `motion.ts`.

---

## The big picture: how the SPA authenticates

```
            ┌──────────────────────── app boot ────────────────────────┐
  main.tsx  │ <AuthProvider> mounts → useEffect → refresh()             │
            │   refresh(): GET /api/v1/auth/me  (cookie auto-attached)  │
            │     200 → setUser/permissions/orgs ; ready=true           │
            │     401 → clear() (anonymous)      ; ready=true           │
            └───────────────────────────────────────────────────────────┘

  Password login (LoginPage → useAuth().login)
    POST /api/v1/auth/login {email,password}
      ├─ mfaRequired=true  → return {mfaRequired,challenge}  (NO cookie yet)
      │     LoginPage prompts TOTP → useAuth().completeMfa(challenge, code)
      │       POST /api/v1/auth/mfa/login {challenge,code}
      │         200 → Set-Cookie cp_session → refresh() → identity loaded
      └─ mfaRequired=false → 200 Set-Cookie cp_session → refresh() → identity loaded

  SSO login (browser-driven, outside this layer)
    IdP redirect → backend SsoSuccessHandler mints cp_session →
    browser lands on  /login?sso=success  with the cookie already set →
    AuthProvider's boot refresh() picks the cookie up → user is signed in

  401 at any later time (interceptor)
    onUnauthorized() → AuthProvider.clear() → user drops to anonymous → route guard → /login
```

The defining property: **there is exactly one path that turns "has cookie" into "is authenticated in
the UI" — `auth.me()` inside `refresh()`.** Password login, MFA login, and SSO all converge on it.
The login response body is *not* used to populate identity (it carries a partial `UserDto` and no
permission set); the SPA always re-reads the authoritative `/auth/me` envelope.

---

## File-by-file reference

### `api.ts`

The **shared axios client and the complete typed surface of every backend endpoint** the SPA calls.
Every network request in the app flows through the single `http` instance exported here. The file is
organized as one axios instance + cross-cutting helpers, then one exported object per backend resource
(`auth`, `orgs`, `users`, `rbac`, `plans`, `subscriptions`, `licenses`, `keys`, `usage`, `audit`,
`sso`, `apiKeys`).

#### `API_BASE` and the `http` instance

```ts
export const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080';
export const http = axios.create({ baseURL: API_BASE, withCredentials: true });
```

- **`API_BASE`** is read from the Vite env var `VITE_API_BASE` (typed in `vite-env.d.ts`), falling
  back to `http://localhost:8080` for local dev. In a built deployment this is baked at build time;
  pointing the SPA at a different API origin is a build-env change, not a runtime one.
- **`withCredentials: true`** is the linchpin of the whole auth model. It tells the browser to send
  (and accept) cookies on cross-origin XHR/fetch — which is what makes the `HttpOnly cp_session`
  cookie ride along on every request automatically. Because the dev SPA (`:5173`) and API (`:8080`)
  are different origins, this also requires the backend's CORS config to set
  `allowCredentials(true)` with an explicit (non-wildcard) origin — which `SecurityConfig`
  (`app.cors.allowed-origins`) does. The two settings are a matched pair; break either and the
  cookie stops flowing.
- The comment block at the instance is explicit about the *why*: the JWT is **never** persisted in
  `localStorage` (XSS-reachable, defeating the HttpOnly cookie — finding P2 #32). The SPA authenticates
  purely via the cookie and never reads or attaches a Bearer token. (The backend still *accepts*
  Bearer for non-browser clients; the SPA simply doesn't use that path.)

#### The unauthorized handler (401 interceptor)

```ts
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(h) { onUnauthorized = h; }
http.interceptors.response.use(r => r, (err) => {
  if (err.response?.status === 401) onUnauthorized?.();
  return Promise.reject(err);
});
```

- A **module-level singleton callback** decouples the axios layer from React. `auth.tsx` registers a
  handler (`setUnauthorizedHandler(() => clear())`) during `AuthProvider` mount; the interceptor
  simply invokes whatever is registered.
- On **any** `401` response from **any** endpoint, the handler fires — this is the global
  "your session ended" signal. When the backend revokes the session (logout elsewhere, token-version
  bump from a password reset/suspend, cookie expiry), the next request returns 401, the interceptor
  calls `AuthProvider.clear()`, the user state empties, and the route guard bounces the user to
  `/login`. No page has to special-case session expiry.
- The error is **re-rejected** (`Promise.reject(err)`) so the calling query/mutation still sees the
  failure and can show its own error state. The interceptor is an observer, not a swallower.
- *Why a settable singleton and not an import?* `api.ts` must not import React/`auth.tsx` (that would
  be a cycle and would couple the transport to the component tree). The callback indirection keeps
  `api.ts` framework-agnostic.

#### `apiErrorMessage(err: unknown): string`

The canonical error-to-string function used across every page's `onError`/error rendering.
Precedence: for an axios error it prefers `response.data.message`, then `response.data.error`, then
the axios `err.message`; for a plain `Error` it returns `err.message`; otherwise `'Unknown error'`.
This matches the backend's RFC-7807 `ProblemDetail`/`ApiException` JSON (which carries a `message`),
so server-supplied human-readable messages surface directly in toasts.

#### Idempotency helpers

```ts
export function newIdempotencyKey(): string { /* crypto.randomUUID() or a timestamp+random fallback */ }
function idempotent(key?) { return { headers: { 'Idempotency-Key': key ?? newIdempotencyKey() } }; }
```

- `newIdempotencyKey` uses `crypto.randomUUID()` when available and falls back to a
  `idm-<base36-time>-<base36-rand>` string for non-secure contexts/older runtimes (so it still works
  on plain HTTP dev or odd browsers).
- `idempotent(key?)` builds the axios config carrying the **`Idempotency-Key` header** the backend's
  idempotency layer consumes. It is applied to every **create** mutation that could be double-submitted
  (org create, plan create, subscription create, license issue, API-key create, key rotate). Callers
  may pass a *stable* key to make a retry safely collapse to the original result; if omitted, a fresh
  key is generated per call. The header name `Idempotency-Key` is exposed by the backend CORS allowed
  headers — another matched pair with `SecurityConfig`.

#### Endpoint wrappers

Each resource object is a thin, **typed** async wrapper over `http`. Two patterns recur and are worth
calling out because they encode real backend contracts:

1. **Tolerant list unwrapping.** Many list endpoints may return either a bare array or a
   `Paged<T>` envelope, so list wrappers do `Array.isArray(data) ? data : data.items`. This keeps the
   client resilient to whether a given endpoint paginates.
2. **Field-name fidelity.** Several wrappers exist specifically to send the **exact** field names the
   backend binds, because getting them wrong is a security/data bug, not just a 400. These are the
   load-bearing ones and carry warning comments in the source.

| Resource | Method → endpoint | Notes / contract |
|---|---|---|
| `auth` | `login(email,password)` → `POST /auth/login` | Returns raw `LoginResponse`; caller inspects `mfaRequired`. |
| | `mfaLogin(challenge,code)` → `POST /auth/mfa/login` | Step 2; exchanges signed challenge + TOTP for the session cookie. |
| | `logout()` → `POST /auth/logout` | Backend denylists the jti + clears the cookie. |
| | `me()` → `GET /auth/me` | **Identity bootstrap.** Normalizes `MeResponse` → `AuthIdentity {user, permissions, orgs}`, defaulting `permissions`/`orgs` to `[]`. |
| | `requestPasswordReset(email)` / `confirmPasswordReset(token,newPassword)` | Reset request/confirm. |
| `orgs` | `list/create/get/members/addMember/removeMember` | `create` is idempotent. |
| `users` | `get(id)` / `update(id, patch)` (PATCH) | Partial update via `Partial<User>`. |
| `rbac` | `roles()` / `permissions()` / `assignRole(userId,{roleCode,orgId?})` | Permissions list unwraps `PagedResponse<PermissionDto>.items`. |
| `plans` | `list/get/create/update` | `create` idempotent; payload sends inline `permissions`/`features`. |
| | `setPermissions(id, permissionCodes)` → `POST /plans/{id}/permissions` | **P0-2:** body MUST be `{ permissionCodes }`. Sending `{ permissions }` binds null and DELETES every entitlement permission on the plan. |
| | `setFeatures(id, features)` → `POST /plans/{id}/features` | `features` is a JSON **object** (`Map<String,Object>`), never an array. |
| `subscriptions` | `listForOrg/create/get/suspend/cancel/reactivate` | `create` idempotent. |
| | `addOverride(id, override)` → `POST /subscriptions/{id}/overrides` | Adds **one** override per call (single `{type,key,value}` body, not an array). |
| | `removeOverride(id, overrideId)` | |
| `licenses` | `issue(subId, payload?, key?)` → `POST /subscriptions/{subId}/licenses` | Idempotent issue; returns `IssuedLicense` (carries the inline `.lic` + downloadUrl). |
| | `listForSubscription(subId, status?)` / `list({subscriptionId, status?})` → `GET /licenses?subscriptionId=…` | **Tenant-leak fix:** `subscriptionId` is mandatory; there is NO unscoped global enumeration. `list` is an alias that forwards `subscriptionId`. |
| | `revoke(jti, reason?)` → `POST /licenses/{jti}/revoke` | |
| | `download(jti)` → `GET /licenses/{jti}/download` (blob) | See below. |
| `keys` | `list()` → `GET /admin/keys` ; `rotate(key?)` → `POST /admin/keys/rotate` | `rotate` idempotent. |
| `usage` | `forSubscription(subId, {from?,to?})` → `GET /subscriptions/{subId}/usage` | |
| `audit` | `list(params)` → `GET /audit?…` ; `forOrg(orgId, params)` → `GET /orgs/{orgId}/audit?…` | Coerces a bare-array response into a synthetic `Paged` so callers always get `{items,total,page,pageSize}`. |
| `sso` | `list/create/remove/test` | List/create go through `decodeSsoProvider` (below). |
| `apiKeys` | `list/create/remove` | List/create go through `normalizeApiKey` (below); create surfaces the one-time plaintext. |

Three wrappers do **client-side normalization** because the backend returns JSON-encoded strings or
non-uniform shapes:

- **`decodeSsoProvider(dto): SsoProviderView`** — the SSO API returns a *list* of providers (one per
  protocol), each with `config` as an opaque **JSON string**. This parses that string into a typed
  `SsoProviderConfig`, and is **tolerant of malformed JSON** (falls back to `{}` instead of throwing —
  a bad config row must not white-screen the SSO page; covered by an explicit test). `sso.list` maps
  it over every row; `sso.create` decodes the single returned dto.
- **`normalizeApiKey(dto): ApiKey`** + private `parseScopes` — `ApiKeyDto.scopes` arrives as a JSON
  string (e.g. `'["license.read"]'`). `parseScopes` JSON-parses it to an array, tolerating both a
  plain comma-separated fallback and outright garbage (returns `[]` rather than crashing on `.map`).
  It also renames `keyPrefix → prefix`.
- **`apiKeys.create`** has a bespoke response type: the backend's `CreateResponse` includes the
  one-time **plaintext** key in the `key` field (shown to the user exactly once). The wrapper surfaces
  it as `ApiKey.plaintext` and copies `keyPrefix → prefix`, falling back to the requested scopes if
  the response omits them.

**`licenses.download(jti)`** is the one wrapper that does binary + header work:

```ts
const resp = await http.get(`/api/v1/licenses/${jti}/download`, { responseType: 'blob' });
const cd = resp.headers['content-disposition'];
let filename = `${jti}.lic`;
if (cd) { const m = /filename\*?=(?:UTF-8'')?["']?([^;"']+)["']?/i.exec(cd); if (m?.[1]) filename = decodeURIComponent(m[1]); }
return { blob: resp.data as Blob, filename };
```

It requests `responseType: 'blob'`, then parses the `Content-Disposition` header to recover the
server-suggested filename (handling both `filename=` and RFC-5987 `filename*=UTF-8''…`), defaulting to
`{jti}.lic`. The returned `{blob, filename}` is handed straight to `triggerDownload` (see
`download.ts`) by `LicensesPage`. Note `Content-Disposition` must be in the backend's CORS
**exposed** headers for the client to read it — which `SecurityConfig` does.

**Testing.** `api.test.ts` pins the load-bearing contracts directly against the wrappers (with `http`
spied): `setPermissions` sends `{permissionCodes}` and never `permissions` (P0-2); `setFeatures` sends
an object not an array; create wrappers attach an `Idempotency-Key` (and honor an explicit one); SSO
create posts `{type, config:object}`; `decodeSsoProvider`/`normalizeApiKey` parse JSON strings and
tolerate malformed input; `licenses.list*` always carries `subscriptionId`; and `apiKeys.create`
surfaces the plaintext from `CreateResponse.key`.

---

### `types.ts`

The **TypeScript mirror of the backend's DTOs and domain enums** — the shared vocabulary for the whole
SPA. No runtime code; pure `interface`/`type` declarations. The comments document the exact backend
field names so UI code can't silently diverge from the wire contract. Highlights and the contracts they
encode:

| Type | Shape / role | Contract notes |
|---|---|---|
| `Role` | `'SUPER_ADMIN' \| 'ORG_OWNER' \| 'ORG_ADMIN' \| 'ORG_MEMBER' \| 'VIEWER'` | The fixed role vocabulary. |
| `User` | `{id,email,fullName?,status?,superAdmin?,createdAt?,lastLoginAt?}` | Mirrors backend `UserDto`. **Field is `fullName` (not displayName) and `superAdmin`.** Permissions/roles are deliberately NOT on the user. |
| `OrgMembership` | `{orgId,slug?,name?,role}` | One entry of the `/auth/me` org list. |
| `AuthIdentity` | `{user, permissions: string[], orgs}` | The **unified identity** the SPA actually uses. Both login completion and `/auth/me` normalize to this. |
| `LoginResponse` | `{accessToken?,expiresAt?,user?,mfaRequired?,mfaChallenge?,mfaChallengeExpiresAt?}` | Raw `/auth/login` body. The MFA branch returns `mfaRequired=true` + a short-lived `mfaChallenge` and **no session**. |
| `MeResponse` | `{user, orgs, permissions}` | Raw `/auth/me` envelope; the permission set lives here, **not** on `user`. |
| `Organization`, `OrgMember` | Org + membership rows. | |
| `Permission`, `RoleDef` | RBAC catalog types. | |
| `Plan`, `PlanFeature` | Plan mirror. | `active` (boolean) + `tier` (no `status` string); `features` is `Record<string,unknown>` (a `Map`), **not** an array. |
| `SubscriptionStatus`, `SubscriptionOverride`, `Subscription` | Subscription domain. | `override.type ∈ {PERMISSION_ADD, PERMISSION_REMOVE, FEATURE_SET}`. |
| `License`, `IssuedLicense` | License row + issue response. | The list endpoint is subscription-scoped and lacks org/plan; `orgId/orgName/planCode` are **enriched client-side** by `LicensesPage`. `IssuedLicense` carries the inline `license` string + `downloadUrl`. |
| `SigningKey` | Ed25519 signing key row (`status ∈ ACTIVE/RETIRED/PENDING`). | |
| `UsageEvent/Series/Quota/Report` | Usage analytics shapes. | |
| `AuditEntry` | One audit-log row (`actorId/email, action, target*, payload, ip, occurredAt`). | |
| `SsoType`, `SsoProviderConfig`, `SsoProviderDto`, `SsoProviderView` | SSO. | `SsoProviderDto.config` is a JSON **string**; `SsoProviderView.config` is the decoded typed object (`SsoProviderConfig` has an index signature for unknown keys). One provider per `type`. |
| `ApiKeyDto`, `ApiKey` | API key wire vs UI shape. | `ApiKeyDto.scopes` is a JSON string; `ApiKey.scopes` is parsed to `string[]`; `ApiKey.plaintext` is present only on the one-time create response (backend field `key`). |
| `Paged<T>` | `{items, total, page, pageSize}` | Generic pagination envelope used by every tolerant list unwrap. |
| `ApiError` | `{status, code?, message, details?}` | Client-side error shape. |

The recurring theme: **wire shapes (`*Dto`) vs. normalized UI shapes.** Where the backend emits a
JSON-string-inside-JSON (`SsoProviderDto.config`, `ApiKeyDto.scopes`), `types.ts` declares both the
raw `Dto` and the decoded view, and `api.ts` owns the conversion. This keeps every component working
against clean, typed objects.

---

### `auth.tsx`

The **React authentication context** — the bridge from the cookie-based backend session to the
component tree. Exports `AuthProvider` (the context provider, mounted in `main.tsx`), `useAuth()` (the
hook every page/guard uses), and the `LoginResult` type.

#### `LoginResult`

A discriminated union returned by `login()`:

```ts
type LoginResult =
  | { mfaRequired: false }
  | { mfaRequired: true; challenge: string; challengeExpiresAt?: string };
```

`LoginPage` switches on `mfaRequired` to decide whether to navigate onward or render the TOTP prompt.

#### `AuthCtx` (the context value / `useAuth()` return)

| Member | Type | Purpose |
|---|---|---|
| `user` | `User \| null` | Current identity, or null when anonymous. |
| `permissions` | `string[]` | Flat authority/permission codes from `/auth/me`. |
| `orgs` | `OrgMembership[]` | The user's org memberships. |
| `ready` | `boolean` | **True only after the initial cookie bootstrap settles.** Guards must wait on this before redirecting, or they'd bounce a logged-in user on first paint. |
| `loading` | `boolean` | Convenience `!ready`. |
| `login(email,password)` | `=> Promise<LoginResult>` | Step-1 password login. |
| `completeMfa(challenge,code)` | `=> Promise<void>` | Step-2 MFA exchange. |
| `logout()` | `=> Promise<void>` | Backend logout + local clear. |
| `refresh()` | `=> Promise<void>` | Re-read `/auth/me`. |
| `hasPermission(perm)` | `=> boolean` | Client-side permission gate. |

#### State and bootstrap

`AuthProvider` holds four pieces of state: `user`, `permissions`, `orgs`, `ready`. A memoized
`clear()` empties the identity (used on logout and on 401).

**`refresh()`** is the single identity loader:

```ts
const refresh = useCallback(async () => {
  try { const id = await auth.me(); setUser(id.user); setPermissions(id.permissions); setOrgs(id.orgs); }
  catch { clear(); }
  finally { setReady(true); }
}, [clear]);
```

It calls `auth.me()` — which the browser authenticates with the `cp_session` cookie — and on success
populates identity, on failure (401 → no/invalid cookie) clears it. Either way it sets `ready=true`,
so the app can stop showing a loading state. The source comment is explicit that this cookie bootstrap
is **the only way a post-SSO browser becomes authenticated**: after SSO the browser holds nothing but
the cookie and an `?sso=success` query flag, so reading the cookie via `/auth/me` is the sole
mechanism — and it doubles as session-restore across reloads with **zero** JS-readable token storage
(findings P1-16 / P2 #32).

**Mount effect** registers the 401 handler and kicks off the first bootstrap:

```ts
useEffect(() => {
  setUnauthorizedHandler(() => { clear(); });   // wire api.ts interceptor → context
  void refresh();                               // initial cookie-based identity load
}, [refresh, clear]);
```

This is the join between `api.ts` and `auth.tsx`: the axios 401 interceptor now drops the user to
anonymous whenever any request 401s.

#### Login flows

- **`login(email,password)`** calls `auth.login`. If the response says `mfaRequired`, it returns the
  challenge (and does **not** load identity — no session exists yet). Otherwise the backend has set the
  cookie, so it calls `refresh()` to load the full `/auth/me` envelope (crucially, the permission set,
  which is *not* on the login `UserDto`) and returns `{mfaRequired:false}`.
- **`completeMfa(challenge,code)`** calls `auth.mfaLogin` (which sets the cookie on success) then
  `refresh()`. Same convergence on `/auth/me`.
- **`logout()`** calls `auth.logout` (backend denylists the jti + clears the cookie) inside a
  try/catch — logout is best-effort client-side, so even a failed request still `clear()`s local
  state. The catch comment notes the backend is the authority on cookie/jti teardown.

#### `hasPermission(perm)` — client-side gating

```ts
if (!user) return false;
if (user.superAdmin) return true;
if (permissions.includes('SUPER_ADMIN') || permissions.includes('*')) return true;
return permissions.includes(perm);
```

A super-admin (either via the `user.superAdmin` flag or a `SUPER_ADMIN`/`*` authority in the set)
passes everything; otherwise it's a literal membership check against the `/auth/me` authority list.
This drives UI affordance gating (hiding buttons/nav the user can't use). **It is convenience only:**
the backend re-checks every action with `@PreAuthorize` against live user state, so a tampered client
gains nothing — the SPA gate just avoids showing actions that would 403.

#### Value memoization & `useAuth`

The context value is `useMemo`'d over all state + callbacks so consumers don't re-render
gratuitously. `useAuth()` reads the context and throws `'useAuth must be used inside AuthProvider'`
if used outside the provider — a fail-fast guard against mis-wiring.

**Testing.** `auth-contract.test.ts` pins the `auth.me` normalization (permissions/orgs come from the
envelope, default to `[]` when absent) and the MFA challenge passthrough on `auth.login` / the
`/auth/mfa/login` body shape — i.e. the exact contract this provider relies on.

---

### `queryClient.ts`

The **singleton TanStack Query `QueryClient`** that backs every `useQuery`/`useMutation` in the app
(provided at the root in `main.tsx`). It centralizes cache and retry policy so individual pages don't
repeat it:

```ts
queries: {
  retry: (count, err) => {
    const status = err?.response?.status;
    if (status === 401 || status === 403 || status === 404) return false;  // never retry these
    return count < 2;                                                       // else up to 2 retries
  },
  staleTime: 30_000,            // data fresh for 30s — no refetch storms on navigation
  refetchOnWindowFocus: false,  // admin tool; don't refetch every tab focus
}
```

Behavior and rationale:
- **Retry policy is status-aware.** `401` (unauthenticated — the interceptor already handles session
  loss), `403` (forbidden — retrying won't help and re-triggers backend rate limits/audit noise), and
  `404` (not found) are **never** retried. Anything else (transient 5xx/network) retries up to 2 times.
- **`staleTime: 30_000`** keeps fetched data fresh for 30 seconds, so moving between pages that share a
  query key reuses the cache instead of re-hitting the API.
- **`refetchOnWindowFocus: false`** suits an internal admin console — alt-tabbing back shouldn't spam
  the backend with refetches.
- Mutations use library defaults; pages wire their own `onSuccess`/`onError` (typically
  `invalidateQueries` + `apiErrorMessage` toast), as seen in `LicensesPage` (`revokeMut`
  invalidates `['licenses','sub',subId]`).

Query keys are conventionally hierarchical arrays (`['orgs']`, `['org', orgId, 'subscriptions']`,
`['licenses','sub',subId]`) so a mutation can invalidate a precise subtree.

---

### `download.ts`

A tiny **browser blob-to-file helper**, the only DOM-touching code in this layer:

```ts
export function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}
```

- Creates an **object URL** from the blob, builds a throwaway `<a download>` element, clicks it
  programmatically to trigger the browser's save flow, then removes the element.
- **Revokes the object URL after 1.5s** (`setTimeout`) rather than immediately — revoking too early
  can abort the download in some browsers; the short delay lets the navigation start, then frees the
  memory.
- **How it fits:** `LicensesPage`'s download mutation calls `licenses.download(jti)` (which returns
  `{blob, filename}` with the server-suggested name) and pipes it straight into `triggerDownload`, so
  an issued `.lic` license file lands on disk with the backend's chosen filename. Keeping this DOM
  side-effect isolated here keeps `api.ts` pure transport and pages declarative.

---

### `vite-env.d.ts`

Vite client **type declarations**. References `vite/client` for `import.meta` typings and augments
`ImportMetaEnv` with the one project-specific env var:

```ts
/// <reference types="vite/client" />
interface ImportMetaEnv { readonly VITE_API_BASE?: string; }
interface ImportMeta { readonly env: ImportMetaEnv; }
```

This is what makes `import.meta.env.VITE_API_BASE` type-safe in `api.ts`. Adding any new
build-time `VITE_*` variable means declaring it here so the rest of the SPA gets it typed.

---

## Cross-cutting contract & invariants (quick reference)

- **Cookie, not token.** Auth is the `HttpOnly cp_session` cookie + `withCredentials: true`. The SPA
  never reads/stores/attaches a JWT. Don't reintroduce `localStorage` token handling (P2 #32).
- **One identity source.** Every login path (password, MFA, SSO) converges on `auth.me()` inside
  `refresh()`. The login response body is not trusted for identity; `/auth/me` is.
- **`withCredentials` ↔ CORS pair.** The cookie only flows because the backend sends
  `allowCredentials(true)` with an explicit origin. Wildcard CORS would silently break login.
- **Idempotency-Key on creates.** Every create/rotate mutation sends the header; the name is in the
  backend's allowed CORS headers. Pass a stable key for safe retries.
- **Field-name fidelity is load-bearing.** `setPermissions` → `{permissionCodes}` (wrong name deletes
  entitlements, P0-2); `setFeatures` → object not array; `licenses.list*` → mandatory `subscriptionId`
  (tenant-leak fix). These have pinning tests; keep them green.
- **Tolerant parsing never white-screens.** `decodeSsoProvider`/`parseScopes` swallow malformed JSON
  and return safe empties.
- **401 is global.** A single interceptor + `setUnauthorizedHandler` turns any 401 into an app-wide
  sign-out. `403/404/401` are non-retryable in `queryClient`.
- **Client gating is cosmetic.** `hasPermission` only shapes the UI; the backend `@PreAuthorize`
  re-checks every request against live user state.
