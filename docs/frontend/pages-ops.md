# Admin UI — Operations & Detail Pages (Aurora Glass)

## Module overview

This document covers the **operational, drill-down half** of the `admin-ui` page tree — the screens
an admin reaches *after* picking an entity from a list, plus the route map that wires every page
together. Where the list pages (orgs, plans, dashboard) are about *browsing*, these pages are about
*acting*: issuing and revoking licenses, suspending/cancelling subscriptions, rotating signing keys,
minting one-time API-key secrets, configuring SAML/OIDC, charting usage, and reading the immutable
audit trail.

Concretely, this file documents:

| Route | Component | What it does |
|---|---|---|
| `/subscriptions/:subId` | `SubscriptionDetailPage` | One subscription: overview, overrides, license table, lifecycle + license actions |
| `/subscriptions/:subId/usage` | `UsagePage` | recharts area + horizontal-bar charts of metered events and quota burn-down |
| `/licenses` | `LicensesPage` | org → subscription scope picker, then a filterable license table with row actions |
| `/keys` | `KeysPage` | Ed25519 signing-key inventory + a confirmed rotation flow |
| `/orgs/:orgId/api-keys` | `ApiKeysPage` | API-key table + create dialog + **one-time** plaintext-secret reveal |
| `/orgs/:orgId/sso` | `SsoConfigPage` | per-protocol (SAML/OIDC) IdP config form with a "test connection" action |
| `/audit` | `AuditPage` | audit log table with a multi-field "apply" filter bar |
| (all) | `App` / `AppRoutes` (`routes.tsx`) | the single `<Routes>` map: public auth routes vs. protected shell |

### How these pages fit the app

Every page here is a leaf rendered into the `AppShell`'s `<Outlet>` (see `routes.tsx` → the protected
branch). They share five infrastructural pillars, all documented once below and then referenced per
page so the per-page sections stay focused:

- **Data layer** — TanStack Query (`@tanstack/react-query`) `useQuery`/`useMutation`, hitting the typed
  client in `@/lib/api`. The session is a backend-set **HttpOnly `cp_session` cookie**; `http` (axios)
  is created with `withCredentials: true` and never reads or attaches a Bearer token, so these pages
  carry **no auth code** — they just call `api.*`.
- **Permission gating** — `<PermissionGate permission="…">` wraps any destructive/privileged control;
  it renders children only if `useAuth().hasPermission(code)` is true (super-admins and a `"*"`/
  `"SUPER_ADMIN"` authority short-circuit to allowed). This is **UI affordance**, not enforcement —
  the backend `@PreAuthorize` is the real gate (see `docs/backend/auth.md`). A hidden button is a
  convenience, not a security boundary.
- **Layout chrome** — `PageHeader` (title/description/breadcrumb/actions) + `Card`/`CardHeader`/
  `CardBody` glass panels + `DataTable` (paginated, skeleton-loading, empty-state table) + `Dialog`
  (focus-trapped modal). All from `@/components/ui` and `@/components`.
- **Feedback** — `useToast()` (`toast.success` / `toast.error`) for mutation outcomes; `apiErrorMessage(e)`
  unwraps the RFC-7807/`{message}` body into a string.
- **Motion** — `framer-motion` variants from `@/lib/motion` (a barrel re-export of `@/styles/motion`):
  `fadeRise` (entrance, opacity + 12px rise) and `staggerContainer` (0.05s child stagger). The shell
  also wraps the `<Outlet>` in a `pageTransition` so every one of these pages fades/slides in on
  navigation "for free."

> **The Aurora Glass system in one paragraph.** Surfaces are frosted glass: translucent white fills
> (`bg-white/70…90`), `backdrop-blur`, hairline rings (`ring-slate-900/5`), and layered `shadow-glass*`.
> Text uses a semantic ink scale (`text-ink`, `-soft`, `-muted`, `-faint`, `-ghost`). The brand accent
> is indigo→violet (`bg-aurora-primary`, `from-indigo-50 to-violet-50`, `bg-aurora-chip`). Motion is
> deliberately restrained — transform + opacity only, ≤220ms — and globally honors OS "reduce motion"
> via `<MotionConfig reducedMotion="user">` (wired in `main.tsx`) plus a CSS net. None of the pages
> below re-derive any of this; they compose the tokens.

---

## The route map

### `App.tsx`

A one-liner. `App` renders `<AppRoutes />` and nothing else — there is no provider nesting here. All
the global providers (`BrowserRouter`, `QueryClientProvider`, `ToastProvider`, `AuthProvider`,
`MotionConfig`, `ErrorBoundary`) are mounted **above** `App` in `main.tsx`. So `App` is purely the
"where do routes live" seam; keep it trivial.

```tsx
export function App() {
  return <AppRoutes />;
}
```

### `routes.tsx`

**`AppRoutes`** — the single `react-router-dom` `<Routes>` table for the whole SPA. It encodes the app's
two-tier structure: a handful of **public** auth routes, and everything else behind a **protected**
shell.

**Structure (in order):**

```
<Routes>
  /login                       → LoginPage                     (public)
  /password-reset/request      → PasswordResetRequestPage      (public)
  /password-reset/confirm      → PasswordResetConfirmPage      (public; token in ?query, not path)
  <Route element={ <ProtectedRoute><AppShell/></ProtectedRoute> }>   ← layout/guard wrapper
     /                          → DashboardPage
     /orgs                      → OrgsListPage
     /orgs/:orgId               → OrgDetailPage
     /orgs/:orgId/sso           → SsoConfigPage        ★ this doc
     /orgs/:orgId/api-keys      → ApiKeysPage          ★ this doc
     /plans                     → PlansListPage
     /plans/:planId/edit        → PlanEditPage
     /subscriptions/new         → SubscriptionCreateWizard
     /subscriptions/:subId      → SubscriptionDetailPage   ★ this doc
     /subscriptions/:subId/usage→ UsagePage             ★ this doc
     /licenses                  → LicensesPage          ★ this doc
     /keys                      → KeysPage              ★ this doc
     /audit                     → AuditPage             ★ this doc
  </Route>
  *                            → <Navigate to="/" replace>   (catch-all)
</Routes>
```

**Key design points:**

- **The protected branch is a layout route.** The parent `<Route>` has no `path` — only an `element`
  of `<ProtectedRoute><AppShell/></ProtectedRoute>`. `ProtectedRoute` blocks until the cookie-based
  `/me` bootstrap settles (`ready`), then either renders children or `<Navigate to="/login">` with
  `state.from` = the attempted path. `AppShell` renders the nav + header and an `<Outlet>`; each child
  route below paints into that outlet. So **every** ops page automatically inherits the auth guard,
  the chrome, and the route-transition animation without opting in.
- **Password-reset token lives in `?token=`, not the path.** There's an explicit code comment: the
  query-param form keeps the token out of referrer headers, server access logs, and browser-history
  path segments. (`PasswordResetConfirmPage` reads it from the query string.)
- **Catch-all redirects, never 404s.** `path="*"` → `<Navigate to="/" replace>`. An unknown URL
  silently lands on the dashboard (which, if unauthenticated, the guard then bounces to `/login`).
  `replace` keeps the bad URL out of history.
- **Permission-scoped nav is NOT here.** `routes.tsx` registers every route unconditionally; what an
  admin can *see in the sidebar* is filtered separately in `AppShell` (`NAV.filter(hasPermission)`),
  and what they can *do on a page* is gated by `<PermissionGate>` inside each page. Routes themselves
  are not permission-guarded — visiting `/keys` without `key.rotate` still renders the read-only table;
  the rotate button just won't appear, and the backend rejects an unauthorized call anyway.

---

## `SubscriptionDetailPage.tsx` — `/subscriptions/:subId`

The single-subscription cockpit. It shows the subscription's overview + overrides, lists every license
issued for it, and exposes the subscription **lifecycle** actions (suspend/cancel) and **license**
actions (issue/download/revoke). It is the hub both `LicensesPage` rows and the breadcrumb on
`UsagePage` link back to.

**Route param / identity:** `const { subId = '' } = useParams()`. All queries are `enabled: !!subId`.

**Queries**

| Key | Fn | Notes |
|---|---|---|
| `['subscription', subId]` | `subscriptions.get(subId)` | `GET /api/v1/subscriptions/{id}` → the `Subscription` (status, seats, dates, `overrides`, denormalized `orgName`/`planName`). |
| `['licenses', 'sub', subId]` | `licenses.listForSubscription(subId)` | `GET /api/v1/licenses?subscriptionId=…`. **This exact query key is shared with `LicensesPage`**, so a revoke on either page invalidates both. |

**Mutations** (each `invalidate`s its query, toasts, and on lifecycle ops closes the confirm dialog):

| Mutation | API call | onSuccess |
|---|---|---|
| `suspendMut` | `subscriptions.suspend(subId)` → `POST …/suspend` | invalidate `['subscription', subId]`, toast "Subscription suspended", clear `confirmAction` |
| `cancelMut` | `subscriptions.cancel(subId)` → `POST …/cancel` | invalidate, toast "Subscription cancelled", clear `confirmAction` |
| `issueMut` | `licenses.issue(subId, ttlDays ? {ttlDays} : undefined)` → `POST …/licenses` (carries an `Idempotency-Key`) | invalidate license list, close issue dialog, reset TTL, toast, then **offer immediate download** |

**Local state:** `issueOpen` (issue dialog), `ttlDays` (string TTL input), `confirmAction:
'suspend' | 'cancel' | null` — a single value drives **one shared confirm `Dialog`** whose title/body/
button vary by which action is pending (a tidy pattern: one modal, two flows).

**Key flows**

- **Issue license (with optional download).** Opening the "Issue license" dialog shows a TTL-in-days
  `Input` (blank → "Defaults to the plan's TTL"). On confirm, `issueMut` posts; in `onSuccess` it
  fires a native `confirm('Download the license now?')` — if accepted it calls `licenses.download(jti)`
  and `triggerDownload(blob, filename)`. Download failure is caught and toasted separately so a
  successful issue is never reported as a failure. *Note:* uses browser `confirm()`/`prompt()` for the
  download-now and revoke-reason prompts rather than a glass dialog — a pragmatic shortcut, the
  one place the Aurora Glass surface gives way to a native dialog.
- **Download an existing license.** Row action `onDownload(jti)` → blob fetch → `triggerDownload`
  (creates an `<a download>`, clicks it, revokes the object URL after 1.5s — see `lib/download.ts`).
- **Revoke a license.** Row action `onRevoke(jti)` → native `prompt('Reason for revocation?')` (optional)
  → `licenses.revoke(jti, reason)` → invalidate + toast. Inline async (not a `useMutation`).
- **Suspend / cancel.** The header buttons set `confirmAction`; the shared dialog explains the
  consequence ("Existing licenses remain valid until expiry unless also revoked") before the mutation
  fires with a `loading` spinner on the confirm button.

**What it renders**

- `PageHeader` — title `Subscription · {planName ?? planCode ?? planId}`, description with the org,
  a **breadcrumb** `Link` back to `/orgs/{orgId}`, and an `actions` cluster:
  - "View usage" (`Link` → `/subscriptions/{subId}/usage`)
  - "Suspend" — only if `sub.status === 'ACTIVE'`, gated `subscription.suspend`
  - "Cancel" (danger) — only if `sub.status !== 'CANCELLED'`, gated `subscription.cancel`
  - "Issue license" — gated `license.issue`
- A `staggerContainer`/`fadeRise` **3-column grid**: an "Overview" `Card` (a `<dl>` of Status
  `StatusBadge`, Seats, Starts, Ends, Created — tabular-nums, divided rows) and a 2-col-spanning
  "Overrides" card (each override → a row with a `Badge`(info) for `type`, a `<code>` for `key`, and
  the stringified `value`; empty → a dashed "No overrides — uses plan defaults" placeholder).
- A "Licenses" `Card` with its own header-level "Issue license" button (gated) and a `DataTable`.
- Two `Dialog`s: the issue dialog and the shared suspend/cancel confirm dialog.

**License table columns:** JTI (first 16 chars + ellipsis, `<code>`), Key (`kid`), Status (computed
client-side: `revokedAt` → `REVOKED`, else `expiresAt < now` → `EXPIRED`, else `ACTIVE`, rendered via
`StatusBadge`), Issued (`toLocaleString`), Expires (`toLocaleDateString`), and a right-aligned actions
cell (Download always; Revoke only when not revoked, gated `license.revoke`).

**Permission gates:** `subscription.suspend`, `subscription.cancel`, `license.issue` (×2 — header and
card), `license.revoke` (per row).

**Loading / error:** while `subQ.isLoading` → `<PageLoader/>`; on `subQ.isError` → a rose error panel
with `apiErrorMessage`. The license table independently shows skeletons (`licsQ.isLoading`) and its own
error string in the empty slot, so a license-list hiccup doesn't blank the whole page.

**Aurora Glass / motion:** glass `Card`s; the overview/overrides grid uses
`variants={staggerContainer} initial="hidden" animate="show"` with each column a `fadeRise` child; the
license card is a standalone `fadeRise`. Dialogs animate via the `Dialog` component's built-in
`motion-safe:animate-scale-in` panel over a fading backdrop.

---

## `UsagePage.tsx` — `/subscriptions/:subId/usage`

The metered-usage analytics view for one subscription: an **area chart** of feature events over a
selectable date window and a **horizontal stacked-bar** quota burn-down. This is the most chart-heavy
page in the app and the only consumer of `recharts`.

**Route param:** `subId` from `useParams` (queries `enabled: !!subId`).

**Local state:** `from` / `to` date strings, initialized to `daysAgoIso(30)` and today (both
`YYYY-MM-DD`, sliced from ISO). `daysAgoIso(n)` is a tiny local helper subtracting `n` days.

**Query**

| Key | Fn | Endpoint |
|---|---|---|
| `['usage', subId, from, to]` | `usage.forSubscription(subId, { from, to })` | `GET /api/v1/subscriptions/{subId}/usage?from&to` → `UsageReport { series[], quotas[] }` |

Because `from`/`to` are in the query key, editing either date input **refetches automatically** — there
is no explicit "apply" button here (contrast with `AuditPage`).

**Data shaping (three `useMemo`s):**

- `seriesData` — pivots the API's per-feature `series[]` (each a list of `{ts, quantity}` points) into a
  **wide, per-day** row array: a `Map` keyed by `ts.slice(0,10)` (day), each row `{ ts, [featureKey]:
  sum }`, summing multiple same-day points, then sorted by `ts`. This is the shape recharts wants for a
  multi-series area chart.
- `seriesKeys` — the list of `featureKey`s, used both to render one `<Area>` per feature and to build
  the gradient `<defs>`.
- `quotaData` — maps `quotas[]` to `{ feature, used, remaining: max(0, limit-used) (or 0 if limit
  null), limit }` for the stacked bars (a `null` limit = unlimited → no remaining segment).

`palette` is a fixed 6-color array (indigo, cyan, violet, emerald, amber, pink), indexed `i % length`
so series/quotas colors are stable and wrap gracefully.

**`ChartTooltip` (local component).** A custom recharts tooltip rendered on a frosted `.glass-pop`
panel. There's a load-bearing comment explaining *why* it's custom: recharts' default swatch reads the
series `fill`, but the areas are **gradient-filled** (a `url(#…)` ref), which renders as a blank swatch.
So the tooltip draws its own colored dot from `p.color ?? p.stroke ?? p.fill` and lists each series
name + value (`tabular-nums`). It returns `null` when inactive/empty.

**What it renders**

- `PageHeader` "Usage" with a breadcrumb `Link` back to `/subscriptions/{subId}`.
- A `fadeRise` filter `Card`: two `Field`-wrapped `type="date"` inputs (From / To) in a 2-col grid.
- Then a branch: `isLoading` → `<PageLoader/>`; `isError` → rose error panel; else a
  `staggerContainer` column of two `fadeRise` chart cards:
  1. **"Events over time"** — an `AreaChart` in a fixed-height (`h-80`) `ResponsiveContainer`. Per-series
     vertical `linearGradient` defs (`stopOpacity` 0.28 → 0), a horizontal-only `CartesianGrid`,
     borderless axes with slate ticks, the custom `<Tooltip content={<ChartTooltip/>}>`, a `Legend`, and
     one monotone `<Area>` per feature (2.5px stroke, gradient fill, no dots, 4px active dot). Empty →
     a dashed "No usage data in this window" placeholder.
  2. **"Quota burn-down"** — a vertical-layout `BarChart` (`h-72`): numeric X, category Y of feature
     keys (140px wide), two stacked bars per feature: `used` (indigo `#6366f1`) + `remaining`
     (slate `#e2e8f0`, rounded right cap). Empty → "No quotas configured".

**Accessibility:** each chart's wrapper `<div>` carries `role="img"` and a descriptive `aria-label`
("Area chart of feature events ingested…"; "Horizontal stacked bar chart showing used versus remaining
quota…"), so the otherwise-opaque SVG charts announce their meaning to screen readers.

**Permission gates:** none — this is a read-only analytics view (any admin who can reach the route can
read it; the backend still authorizes the `usage` endpoint).

**Aurora Glass / motion:** glass cards; `fadeRise`/`staggerContainer` entrances; recharts is themed to
match (slate `#64748b` ticks, `#eef0f5` grid, indigo `#c7d2fe` cursor, the `.glass-pop` tooltip). All
chart colors come from the shared `palette`/brand values rather than recharts defaults.

---

## `LicensesPage.tsx` — `/licenses`

The cross-subscription license browser. Its defining trait is the **org → subscription scope picker**:
because the backend `/licenses` endpoint *requires* a `subscriptionId` (the tenant-leak fix — there is
no global unscoped enumeration anymore), this page makes the admin choose an org, then a subscription,
and only then lists that subscription's licenses. There's an explicit comment flagging that a future
scoped backend aggregate endpoint would enable a true cross-subscription view.

**Local state:** `filter` (free-text search), `statusFilter: 'all'|'active'|'expired'|'revoked'`,
`orgId`, `subId`.

**Queries**

| Key | Fn | Enabled when |
|---|---|---|
| `['orgs']` | `orgs.list` | always (populates the org `Select`) |
| `['org', orgId, 'subscriptions']` | `subscriptions.listForOrg(orgId)` | `!!orgId` (populates the subscription `Select`) |
| `['licenses', 'sub', subId]` | `licenses.listForSubscription(subId)` | `!!subId` (the table data) — **same key as `SubscriptionDetailPage`** |

**Cascade reset:** a `useEffect([orgId])` clears `subId` whenever the org changes, so a stale
subscription selection can't survive an org switch.

**Client-side filtering (`useMemo`):** over `licsQ.data`, applies the free-text filter (case-insensitive
substring over `jti + kid`) and the status filter (computing `revoked`/`expired` the same way the detail
page does). Returns `[]` until a sub is chosen.

**Mutation:** `revokeMut` → `licenses.revoke(jti, reason)` → invalidate `['licenses', 'sub', subId]`,
toast "License revoked".

**What it renders**

- `PageHeader` "Licenses" (description: "Issued license tokens, scoped to a subscription").
- A **scope-picker `Card`** (`motion-safe:animate-fade-up`) with two `Field`-wrapped `Select`s in a
  2-col grid: Organization (options `name (slug)`, disabled while `orgsQ.isLoading`) and Subscription
  (options `planName ?? planCode ?? planId · status`, disabled until an org is chosen / while loading;
  hint "Pick an organization first" when no org). The page calls this out in a comment as "a deliberate
  two-step flow" promoted into its own glass panel.
- A `DataTable` whose `rows` are `subId ? data : []` (empty until a sub is picked), with a `toolbar`
  holding the free-text `Input` (max-w-sm) and the status `Select`. `loading` is `!!subId &&
  licsQ.isLoading`.

**Contextual empty message** (a nice progressive-disclosure touch): `"Select an organization to
begin."` → `"Select a subscription to view its licenses."` → the error string → `"No licenses found
for this subscription."`, depending on how far the admin has drilled.

**Columns:** JTI (first 18 chars), Key (`kid`), Status (computed `StatusBadge`), Issued, Expires
(both `toLocaleDateString`, tabular-nums), and a right-aligned actions cell:
- "Subscription" — `Link` to `/subscriptions/{subscriptionId}` (jumps to the detail page).
- "Download" — **only when not revoked** (comment: the backend rejects downloading a revoked license,
  so the action is hidden rather than shown-and-failing). Calls `onDownload`/`triggerDownload`.
- "Revoke" — only when not revoked, **gated `license.revoke`**, danger-tinted; uses native
  `prompt()` for the optional reason, then `revokeMut.mutate`.

**Permission gates:** `license.revoke` (per row).

**Aurora Glass / motion:** the scope-picker card uses the CSS-keyframe `motion-safe:animate-fade-up`
(not a framer variant) — consistent with `PageHeader`'s own entrance; the `DataTable` is the standard
glass-solid table. No page-level framer orchestration here (the shell's route transition supplies the
entrance).

---

## `KeysPage.tsx` — `/keys`

The Ed25519 **signing-key** inventory and rotation console. These are the keypairs that sign the
offline-verifiable license JWTs; old keys stay published in JWKS until explicitly retired, so apps that
cached the old public key keep verifying during the overlap.

**Query:** `['keys']` → `keys.list()` → `GET /api/v1/admin/keys` → `SigningKey[]`.

**Mutation:** `rotateMut` → `keys.rotate()` → `POST /api/v1/admin/keys/rotate` (carries an
`Idempotency-Key`). `onSuccess(k)`: invalidate `['keys']`, close the confirm dialog, toast
``New active key: ${k.kid}``.

**Local state:** `confirmOpen` (the rotation confirm dialog).

**What it renders**

- `PageHeader` "Signing keys" (description explains the JWKS-overlap behavior) with a single gated
  action: "Rotate signing key" (`key.rotate`), which opens the confirm dialog.
- A `DataTable` of keys, columns: **Key ID** (`kid` in a monospace chip — `bg-slate-100/80`, inset
  ring), **Algorithm** (mono), **Status** (`StatusBadge` — `ACTIVE`→success, `RETIRED`→danger,
  `PENDING`→warning), **Created** (`toLocaleString`), **Activated** / **Retired** (each
  `toLocaleString` or an `aria-hidden` em-dash placeholder when null). Loading → skeletons; error →
  `apiErrorMessage` in the empty slot.
- A confirm `Dialog` ("Rotate signing key?") whose body is a **warning callout** (warn-tinted glass
  panel with a triangle-alert SVG icon): "This action takes effect immediately… Retire the previous key
  only after dependent apps have refreshed their JWKS." Footer: "Cancel" + a "Rotate" button with
  `loading={rotateMut.isPending}`.

**Permission gates:** `key.rotate` (header action only). The table is otherwise read-only.

**Aurora Glass / motion:** glass `DataTable`; the rotation dialog uses the warn palette
(`bg-warn-50/70`, `text-warn-700`, an icon chip) to signal a high-consequence operation, plus the
`Dialog`'s built-in scale-in. No page-level framer variants — entrance comes from the shell transition.

---

## `ApiKeysPage.tsx` — `/orgs/:orgId/api-keys`

Per-org programmatic-access keys (for CI pipelines / integrations). The signature behavior is the
**one-time plaintext secret reveal**: the full key value is returned only once, by the create response,
and shown in a "copy it now, you can't see it again" dialog.

**Route param:** `orgId` from `useParams` (query `enabled: !!orgId`).

**Query:** `['org', orgId, 'api-keys']` → `apiKeys.list(orgId)` → `GET /api/v1/orgs/{orgId}/api-keys`.
The client `normalizeApiKey`s each DTO (parsing the JSON-string `scopes` into an array, mapping
`keyPrefix`→`prefix`).

**Form (react-hook-form + zod):** schema `{ name: min(2), scopes: min(1) }`. `scopes` is a free-text
**comma-separated** string in the form; defaults to `'license.read,license.issue'`.

**Mutations**

| Mutation | Call | onSuccess |
|---|---|---|
| `createMut` | `apiKeys.create(orgId, { name, scopes: split/trim/filter })` → `POST …/api-keys` (Idempotency-Key) | invalidate list, close create dialog, reset form, **`setCreatedKey(k)`** → opens the reveal dialog |
| `deleteMut` | `apiKeys.remove(orgId, id)` → `DELETE …/api-keys/{id}` | invalidate list, toast "API key revoked" |

**Local state:** `openCreate` (create dialog), `createdKey: ApiKey | null` (the just-created key,
carrying the one-time `plaintext` field; non-null drives the reveal dialog).

**Key flow — one-time secret reveal:**

```
[New API key] → create Dialog (name + scopes form)
   submit → createMut → onSuccess(k where k.plaintext = backend `key`)
      → close create dialog, reset form
      → setCreatedKey(k)  ──► reveal Dialog opens
            • role="alert" warn banner: "Store this in a secret manager. We cannot recover it."
            • <pre> showing createdKey.plaintext (break-all, monospace) — or `${prefix}...` fallback
            • [Copy key] → navigator.clipboard.writeText(plaintext) → toast success/fail
      → [Close] → setCreatedKey(null)  (the secret is gone from state; never refetchable)
```

The list query never carries `plaintext` (the DTO has only `prefix`), so once the reveal dialog closes
the secret is irretrievable from the UI — exactly mirroring the backend's hash-at-rest design.

**What it renders**

- `PageHeader` "API keys" with a breadcrumb `Link` back to `/orgs/{orgId}` and a gated "New API key"
  action (`api-key.create`).
- A `DataTable`, columns: **Name** (bold), **Prefix** (mono chip `{prefix}…`), **Scopes** (each scope a
  mono `Badge`(info), wrapped), **Created** (`toLocaleDateString`), **Last used** (`toLocaleString` or
  `—`), **Status** (a `Badge` with a colored dot — danger "Revoked" if `revokedAt`, else success
  "Active"), and a right-aligned **Revoke** action gated `api-key.delete` (native `confirm("Revoke API
  key …?")` before `deleteMut`).
- The create `Dialog` — a `react-hook-form` form (`id="ak-form"`) with Name and "Scopes
  (comma-separated)" `Field`s (zod errors surfaced inline); the footer Create button submits the form
  via `form="ak-form" type="submit"` with `loading={createMut.isPending}`.
- The reveal `Dialog` (described above).

**Permission gates:** `api-key.create` (header), `api-key.delete` (per row).

**Aurora Glass / motion:** glass cards/dialogs; the reveal banner uses the warn palette + a
`role="alert"`; the secret `<pre>` sits on an inset-shadow frosted panel (`shadow-glass-inset`). No
page-level framer variants.

---

## `SsoConfigPage.tsx` — `/orgs/:orgId/sso`

Per-org single-sign-on configuration. An org may have **one provider per protocol** (one SAML, one
OIDC); a left-rail protocol picker selects which provider the right-hand form edits, and a "Test
connection" action validates a saved provider.

**Route param:** `orgId` (query `enabled: !!orgId`).

**Query:** `['org', orgId, 'sso']` → `sso.list(orgId)` → `GET /api/v1/orgs/{orgId}/sso`. The API
returns a **list** of `{id, type, config(JSON string), enabled}`; `decodeSsoProvider` parses each
`config` string into a typed `SsoProviderView` (tolerant of bad JSON → `{}`).

**Protocol selection:** local `protocol: SsoType = 'SAML'`. `current = useMemo(providers.find(p =>
p.type === protocol))` — the provider matching the selected protocol (or `undefined` = "new provider").

**Form (react-hook-form + zod):** one schema covers both protocols (all fields optional except the
boolean `enabled`; URL fields validated with `.url().optional().or(literal(''))`). `configToForm(current)`
maps a stored provider into form values — **crucially it never echoes `clientSecret`** (always `''`,
with a comment "never echo back a stored secret"). A `useEffect([current, form])` resets the form
whenever the selected protocol/provider changes.

**Mutations**

| Mutation | Behavior |
|---|---|
| `saveMut` | Builds a `config` object by **spreading the existing stored config first** (preserving unknown keys), then overlaying the typed fields for the active protocol and **deleting the other protocol's keys** (SAML save strips `issuer/clientId/discoveryUrl`; OIDC save strips `metadataUrl/metadataXml`). The client secret is only included **when the admin typed a new one** (blank = leave unchanged). Then `sso.create(orgId, { type: protocol, config })`. onSuccess: invalidate + toast "SSO configuration saved". |
| `testMut` | Throws "Save the provider before testing." if `!current`; else `sso.test(orgId, current.id)`. The result `{ok?, message?}` toasts as error when `ok === false`, success otherwise. |

**What it renders** — a 2-col grid (`[18rem minmax(0,1fr)]`), `staggerContainer`/`fadeRise`:

- **Left rail (sticky) "Protocols" `Card`.** Two big toggle `<button>`s (SAML / OIDC), each with
  `aria-pressed`, an avatar glyph ("S"/"O"), the protocol label + blurb (from a `PROTOCOL_META` map),
  and a status `Badge` per protocol: "Enabled"/"Configured"/"Not set" depending on whether a provider
  exists and is enabled. The selected button gets the indigo→violet gradient + glow treatment; the rest
  get a hover-lift (`hover:-translate-y-px hover:shadow-glass-sm`). Clicking sets `protocol`.
- **Right "Identity provider" `Card`.** Header shows a status `Badge` ("SSO enabled" / "Saved, disabled"
  / "New provider"). The body is the config `<form>`:
  - Always: a **Protocol** `Select` (mirrors the rail) and an **Enabled** checkbox styled as a glass
    toggle row.
  - **SAML branch:** "IdP metadata URL" (`Input`, url-validated, hint "Or paste XML below") + "IdP
    metadata XML" (`Textarea`, 8 rows, monospace).
  - **OIDC branch:** "Issuer", "Discovery URL" (url-validated), then a 2-col "Client ID" + "Client
    secret" (`type="password"`, `autoComplete="new-password"`, hint "Leave blank to keep current",
    `••••••••` placeholder when a provider exists).
  - Always: "Allowed email domains (comma-separated)" (hint "Required for JIT provisioning; blank
    denies JIT").
  - Footer (top-bordered): "Save configuration" (submit, `loading=saveMut.isPending`) + "Test
    connection" (`disabled={!current}`, `loading=testMut.isPending`) + a helper note when `!current`
    explaining you must save before testing.

**Loading:** `providersQ.isLoading` → `<PageLoader/>`. Breadcrumb `Link` back to `/orgs/{orgId}`.

**Permission gates:** none rendered on this page — the save/test buttons are always shown; the backend
authorizes the SSO endpoints. (If gating is desired it would wrap the footer actions.)

**Accessibility:** protocol toggles use `aria-pressed`; the secret field uses
`autoComplete="new-password"` to suppress autofill; checkboxes/labels are properly associated via
`htmlFor`. URL validation errors render via `Field error` + the input's `invalid` rose state.

**Aurora Glass / motion:** the strongest "designed" page here — the selected protocol card uses
`bg-gradient-to-r from-indigo-50 to-violet-50` + `bg-aurora-primary` glyph with `shadow-glow`;
unselected cards hover-lift; the whole grid is a `staggerContainer` of `fadeRise` columns; the left
rail is `lg:sticky lg:top-20`.

---

## `AuditPage.tsx` — `/audit`

The control-panel audit log: every write produces an immutable, append-only entry. This page is a
filterable table of those entries.

**Local state:** `filters` (the *draft* filter inputs: `actorId`, `action`, `targetType`, `from`,
`to`) and `applied` (the *committed* copy). They're separate so typing doesn't refetch on every
keystroke — the query keys off `applied`, and the **"Apply" button** commits `setApplied(filters)`.
(Contrast with `UsagePage`, which refetches on every date change.)

**Query:** `['audit', applied]` → `audit.list({ ...applied, page: 1, pageSize: 100 })` → `GET
/api/v1/audit?…`. `audit.list` builds the query string skipping empty values, and normalizes a bare
array response into a `Paged` shape. `items = useMemo(data?.items ?? [])`.

**What it renders**

- `PageHeader` "Audit log" (description: "Every write in the control panel produces an audit entry.
  Immutable, append-only.").
- A `fadeRise` filter `Card` — a responsive grid (1→6 cols at `xl`, `xl:items-end`) of `Field`-wrapped
  inputs: **Actor** (email or ID), **Action** (e.g. `license.issued`), **Target type** (e.g.
  `subscription`), **From** / **To** (`type="date"`), and a full-width **Apply** `Button` that commits
  the filters.
- A `DataTable` (`pageSize={50}` — client-side paginates the up-to-100 fetched rows) with columns:
  **When** (`occurredAt` `toLocaleString`, 15rem wide, nowrap, tabular-nums), **Actor**
  (`actorEmail ?? actorId ?? '—'`, bold), **Action** (a monospace chip on `bg-aurora-chip` with an
  indigo inset ring — the brand accent), **Target** (`targetType · targetId`, or `—`), **IP** (mono or
  `—`). Loading → skeletons; error → `apiErrorMessage` in the empty slot; otherwise "No audit events."

**Permission gates:** none on the page itself (reaching `/audit` is governed by the nav's `audit.read`
gate in `AppShell` and the backend's authorization of the audit endpoint).

**Aurora Glass / motion:** the filter card is a `fadeRise`; the action chip reuses the `bg-aurora-chip`
brand surface; the table is the standard glass `DataTable`. No per-row motion.

---

## Shared infrastructure these pages rely on (reference)

These are documented in depth elsewhere; summarized here only insofar as the ops pages depend on them.

### `@/lib/api` (the typed client)
- `http` — axios with `withCredentials: true`; a response interceptor calls a registered
  `onUnauthorized` handler on any `401` (wired by `AuthProvider` to clear identity). No Bearer token is
  ever attached; the `cp_session` cookie carries auth.
- `apiErrorMessage(err)` — unwraps `{message}`/`{error}`/`err.message` to a display string (used by
  every page's error path).
- `newIdempotencyKey()` / `idempotent()` — every **create**-style mutation on these pages
  (`licenses.issue`, `keys.rotate`, `apiKeys.create`) sends an `Idempotency-Key` header so a retried
  POST doesn't double-create.
- Endpoint groups used here: `subscriptions.{get,suspend,cancel,listForOrg}`,
  `licenses.{listForSubscription,issue,revoke,download}`, `keys.{list,rotate}`,
  `usage.forSubscription`, `audit.list`, `sso.{list,create,test}` (+ `decodeSsoProvider`),
  `apiKeys.{list,create,remove}` (+ `normalizeApiKey`), `orgs.list`.
- **Tenant-isolation note baked into the client:** `licenses.listForSubscription` (and its `list`
  alias) *require* a `subscriptionId` query param — there is deliberately no unscoped global license
  enumeration. This is exactly why `LicensesPage` has the org→subscription picker.

### `@/lib/auth` + `PermissionGate`
`useAuth()` exposes `hasPermission(code)`: `false` if logged out, `true` for super-admins or a `"*"`/
`"SUPER_ADMIN"` authority, else membership in the `/me` permission set. `<PermissionGate
permission|permissions any fallback>` renders children only if allowed (default `every`, `any` for OR).
Used across these pages to hide privileged buttons. **UI-only** — never the security boundary.

### `@/components/DataTable`
Generic, glass-surfaced (`glass-solid`, `ring-slate-900/5`, `shadow-glass`) table over `Column<T>[]` +
`rowKey`. Built-in **client-side pagination** (`pageSize`, default 25; ops pages override to 50 on
audit), **skeleton** rows on `loading`, an `EmptyState` slot (`empty`), an optional `toolbar` strip, and
optional `onRowClick` (which makes rows keyboard-focusable with `role="button"` + Enter/Space). All ops
tables on this page set `rows`/`columns`/`rowKey`/`loading`/`empty`; `LicensesPage` and `AuditPage` add
toolbars.

### `@/components/ui` primitives
- `PageHeader` — title (`font-display`), description, `breadcrumb`, `actions`; enters with
  `motion-safe:animate-fade-up`.
- `Card`/`CardHeader`/`CardBody` — glass panels (the recurring section container).
- `Dialog` — frosted modal (`bg-white/90 backdrop-blur-glass`, `shadow-glass-xl`,
  `motion-safe:animate-scale-in` panel over a fade-in backdrop). **Accessibility-complete:**
  `role="dialog" aria-modal`, `aria-labelledby`/`describedby`, Escape-to-close, **focus capture +
  restore**, and a **Tab focus trap**. Every confirm/issue/create/reveal dialog on these pages uses it.
- `Field`/`Label`/`Input`/`Textarea`/`Select` — Aurora Glass form fields (frosted at rest, crisp white
  + indigo ring on focus, rose `invalid` state). `Field` auto-wires `aria-describedby`/
  `aria-errormessage` to its child when an `error` is present.
- `Badge`/`StatusBadge` — tonal pills; `StatusBadge` maps a status string to a tone (ACTIVE→success;
  SUSPENDED/EXPIRED/PENDING→warning; CANCELLED/DISABLED/REVOKED/RETIRED→danger; DRAFT/INVITED→info) and
  always renders the label text so meaning never rests on color alone.
- `PageLoader`/`Spinner` — the full-page loading state used on the detail/usage/sso initial fetches.

### Motion (`@/lib/motion` → `@/styles/motion`)
`fadeRise` (opacity + 12px rise, 0.22s ease-out) and `staggerContainer` (0.05s child stagger) are the
two variants these pages import for section entrances. The shell additionally wraps the `<Outlet>` in
`pageTransition` (keyed on `location.pathname` inside `<AnimatePresence mode="wait">`) so each ops page
fades/slides on navigation without its own code. All motion is transform/opacity-only and respects OS
reduced-motion globally via `<MotionConfig reducedMotion="user">`. Charts in `UsagePage` are the
exception to the "no SVG color tricks" rule — handled by the custom `ChartTooltip`.

### `@/lib/download`
`triggerDownload(blob, filename)` — creates a transient `<a download>`, clicks it, and revokes the
object URL after 1.5s. Used by the license download flows in `SubscriptionDetailPage` and
`LicensesPage`.

---

## Cross-cutting notes & gotchas (quick reference)

- **Permission gates are affordances, not enforcement.** Hidden buttons on these pages reduce mistakes;
  the backend `@PreAuthorize` is the actual authority. Don't rely on a missing button for security.
- **Shared query key `['licenses', 'sub', subId]`** is used by both `SubscriptionDetailPage` and
  `LicensesPage`, so a revoke/issue on one refreshes the other when both target the same subscription.
- **Two refetch idioms.** `UsagePage` puts its date inputs *in* the query key → auto-refetch on change;
  `AuditPage` keeps a draft/applied split → refetch only on "Apply". Match the existing page's idiom
  when extending.
- **One-time secrets are truly one-time.** `ApiKeysPage` is the only place a key's `plaintext` exists in
  client state, and only until the reveal dialog closes; the list DTO never carries it.
- **Native `confirm()`/`prompt()` shortcuts.** `SubscriptionDetailPage` (download-now, revoke reason),
  `LicensesPage` (revoke reason), and `ApiKeysPage` (revoke confirm) use browser dialogs rather than the
  glass `Dialog`. Functional but the one spot the Aurora Glass surface yields to the platform.
- **SSO secret hygiene.** `clientSecret` is never echoed back into the form; a save only transmits it
  when freshly typed (blank = unchanged). When changing the save logic, preserve the
  spread-existing-config-then-overlay-and-delete-other-protocol pattern so unknown stored keys survive.
- **Hidden vs. shown destructive actions:** `LicensesPage` *hides* Download/Revoke on already-revoked
  licenses (the backend would reject them) rather than showing-and-failing — prefer hiding actions the
  backend will refuse.
- **Routes are not permission-guarded; nav and controls are.** `routes.tsx` registers everything; the
  sidebar filters by permission (`AppShell`) and pages gate their controls (`PermissionGate`). Visiting
  a route you lack write permission for renders the read-only view.
