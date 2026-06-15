# `admin-ui/src/pages` — Core Pages (Part 1: Auth + Catalog/CRUD)

## Module overview

These are the **screens a control-panel operator actually clicks through** to sign in and to run
the licensing catalog: the three unauthenticated auth screens (`LoginPage`,
`PasswordResetRequestPage`, `PasswordResetConfirmPage`), the post-login `DashboardPage`, and the
organization / plan / subscription CRUD flow (`OrgsListPage`, `OrgDetailPage`, `PlansListPage`,
`PlanEditPage`, `SubscriptionCreateWizard`). They are the front-end counterparts to the backend
documented in `docs/backend/auth.md` and the orgs/plans/subscriptions controllers — the React SPA
the auth package exists to secure.

Every page here follows the same **Aurora Glass** house pattern, so it is worth stating once and
referencing throughout rather than re-deriving per file:

- **Data layer.** All server I/O goes through the typed `@/lib/api` client (an axios instance with
  `withCredentials: true` so the HttpOnly `cp_session` cookie rides every request — the SPA never
  reads or stores the JWT). Reads use **TanStack Query** (`useQuery`), writes use `useMutation`,
  cache freshness is managed with `queryClient.invalidateQueries`. Query keys are conventional
  arrays (`['orgs']`, `['org', orgId, 'members']`, `['plan', planId]`).
- **Forms.** `react-hook-form` + `@hookform/resolvers/zod` + a local `zod` schema. The `<Field>`
  wrapper renders the label, the `error` string from `formState.errors`, and an optional `hint`.
  `<Input invalid>` paints the error ring.
- **Errors & feedback.** API errors are stringified through `apiErrorMessage(e)` (reads RFC-7807
  `message`/`error`, falls back to the axios message). Inline forms render an `role="alert"` glass
  banner; list/CRUD mutations surface results through the global toast (`useToast()`).
- **Permission gates.** UI affordances are wrapped in `<PermissionGate permission="…">`, which calls
  `useAuth().hasPermission`. **This is presentational only** — the backend `@PreAuthorize` is the
  real enforcement; hiding a button never substitutes for server authority. Two gates here were
  re-pointed to the *enforced* authority during remediation (finding **P3**: UI gate codes had
  drifted from the backend authorities — e.g. gating on the non-enforced `org.create`/`plan.create`
  instead of the real `org.write`/`plan.write`).
- **Routing.** Routes are declared in `admin-ui/src/routes.tsx`. The three auth pages are
  **top-level (unauthenticated)**; everything else is nested under `<ProtectedRoute><AppShell/>`,
  which blocks on the cookie bootstrap (`useAuth().ready`) and redirects unauthenticated users to
  `/login` carrying `state.from` for post-login return.
- **Motion.** Shared tokens/variants come from `@/lib/motion` (a barrel re-exporting
  `@/styles/motion`): `DURATION`, `EASE`, `SPRING`, and variants `fadeRise`, `staggerContainer`,
  `hoverLift`. The cardinal rule (stated in `styles/motion.ts`) is **animate only transform +
  opacity**, never layout properties, because the glass `backdrop-filter` makes layout animation
  expensive. Global `<MotionConfig reducedMotion="user">` (in `main.tsx`) plus a CSS net in
  `index.css` honor `prefers-reduced-motion`, so individual pages can use motion freely.

### Route map for the pages in this doc

| Route | Page | Auth | Notes |
|---|---|---|---|
| `/login` | `LoginPage` | public | Two-step (password → optional MFA TOTP); surfaces SSO callback errors |
| `/password-reset/request` | `PasswordResetRequestPage` | public | Always-success messaging (no user enumeration) |
| `/password-reset/confirm` | `PasswordResetConfirmPage` | public | Token in `?token=` query param (kept out of path/referrer) |
| `/` | `DashboardPage` | protected | KPI cards + recharts issuance trend + quick actions |
| `/orgs` | `OrgsListPage` | protected | Searchable table + create dialog |
| `/orgs/:orgId` | `OrgDetailPage` | protected | Tabbed: members / subscriptions / sso / apiKeys / audit |
| `/plans` | `PlansListPage` | protected | Table + create dialog |
| `/plans/:planId/edit` | `PlanEditPage` | protected | Details + entitlement permission picker + features editor |
| `/subscriptions/new` | `SubscriptionCreateWizard` | protected | 5-step provisioning wizard, optional license issue |

---

## File-by-file reference

### `LoginPage.tsx`
**Route `/login` (public).** The front door of the SPA. Renders a split-pane Aurora Glass card —
a decorative brand showcase on the left (hidden under `lg`) and the auth form on the right — and
drives a **two-step login**: email/password first, then a conditional MFA TOTP step. It also
surfaces failures redirected back from the **SSO/IdP callback**.

**Exports:** `LoginPage` (named).

**Auth integration.** Pulls `{ user, ready, login, completeMfa }` from `useAuth()`. The
controller logic is thin because `@/lib/auth` owns the protocol:
- `login(email, password)` → `auth.login` (`POST /api/v1/auth/login`). Returns a discriminated
  `LoginResult`: `{ mfaRequired: false }` (session cookie already set, identity refreshed via
  `/me`) or `{ mfaRequired: true, challenge, challengeExpiresAt }`.
- `completeMfa(challenge, code)` → `auth.mfaLogin` (`POST /api/v1/auth/mfa/login`), then `refresh()`.

**Local state.**

| State | Purpose |
|---|---|
| `error: string \| null` | Inline banner text (login failure, MFA failure, SSO error, expired challenge) |
| `challenge: string \| null` | Holds the signed step-1 MFA challenge; its presence switches the UI into the TOTP form |

**Two forms (two zod schemas):**
- `schema` → `{ email (email), password (min 1) }` for step 1.
- `mfaSchema` → `{ code: /^\d{6}$/ }` for step 2 — exactly six digits, validated client-side before
  the call.

**Key user flows.**
1. **Password submit** (`onSubmit`): clears error, calls `login`. If `res.mfaRequired`, stores
   `res.challenge`, resets the MFA form, and **returns without navigating** — the component
   re-renders into the TOTP form. Otherwise calls `goAfterLogin()`.
2. **MFA submit** (`onSubmitMfa`): guards that `challenge` is still present (else "login session
   expired"), calls `completeMfa(challenge, code)`, then `goAfterLogin()`. A **"Back to sign in"**
   button clears `challenge` + `error` to return to step 1.
3. **Post-login navigation** (`goAfterLogin`): reads `loc.state.from` (set by `ProtectedRoute`) and
   `navigate(from, { replace: true })`, defaulting to `/`.
4. **Already-authenticated short-circuit:** if `ready && user`, renders `<Navigate to={from}>` so a
   logged-in user never sees the login form.

**SSO entry point.** There is **no in-page SSO button**; the IdP login is a server-driven browser
redirect (`/oauth2/**` · `/saml2/**`, handled by the backend `ssoFilterChain` →
`SsoSuccessHandler`, which mints the same `cp_session` cookie). On **success** the IdP callback
redirects to the app root with `?sso=success`; the cookie-based `/me` bootstrap in `AuthProvider`
then signs the user in automatically — **no token handling happens in this page**. On **failure**,
a `useEffect` keyed on `searchParams` maps the `?sso=` code to a human message:

| `?sso=` value | Message shown |
|---|---|
| `success` / absent | (no-op) |
| `unverified` | IdP did not assert a verified email |
| `domain` | Email domain not allowed for this org's SSO |
| anything else | Generic "Single sign-on failed" |

**Aurora Glass / motion.**
- Outer card: `staggerContainer` orchestrates two `fadeRise` children (brand pane + form pane) on
  mount, over a `rounded-3xl` glass surface (`bg-white/60 backdrop-blur-glass backdrop-saturate-150
  shadow-glass-xl ring-1`).
- Brand pane is `aria-hidden` (decorative) with two blurred luminous orbs (one
  `motion-safe:animate-float`) and a radial highlight — pure depth, no content for AT.
- The error banner animates in with a small `y: -4 → 0` fade (`DURATION.fast`, `EASE.out`).
- The MFA input uses `inputMode="numeric"`, `autoComplete="one-time-code"`, `maxLength={6}`, and a
  centered `tracking-[0.5em] tabular-nums` treatment so the six digits read like a code field.

**Accessibility.** Heading + helper text swap between the two steps; the error banner is
`role="alert"`; inputs carry proper `autoComplete` (`email`, `current-password`, `one-time-code`);
the "Back to sign in" affordance is a real `<button>` with a visible focus ring. The brand pane is
hidden from AT.

---

### `PasswordResetRequestPage.tsx`
**Route `/password-reset/request` (public).** A single-field "email me a reset link" form. Mirrors
the backend's **deliberately non-enumerating** contract: it always shows the same success message
regardless of whether the email exists.

**Exports:** `PasswordResetRequestPage`.

**Form / data.** `zod` schema `{ email }`. Submitting calls `auth.requestPasswordReset(email)`
(`POST /api/v1/auth/password-reset/request`) directly (no mutation/cache — there is nothing to
invalidate). On resolve it flips `done = true`; on reject it sets the inline `error`.

**Key behavior.**
- `done` swaps the form for a green `role="status"` confirmation: *"If an account exists for that
  email, a reset link has been sent."* — the same copy whether or not the account exists (no
  user-existence leak; matches `AuthController.requestReset` returning `{status:ok}`
  unconditionally).
- Always renders a "Back to sign in" link to `/login`.

**Aurora Glass / motion.** Single centered `<Card className="bg-white/80">` entering with `fadeRise`.
The success panel fades/rises in (`DURATION.base`), the error banner with the faster `DURATION.fast`
variant. CP brand chip uses `bg-aurora-primary shadow-glow`.

**Accessibility.** Success is `role="status"` (polite), error is `role="alert"` (assertive); the
field is labeled via `<Field htmlFor>`.

---

### `PasswordResetConfirmPage.tsx`
**Route `/password-reset/confirm?token=… (public).** The "choose a new password" landing for the
emailed reset link. The opaque reset token arrives as the **`?token=` query param** — by design,
to keep it out of the URL path, referrer headers, and server access logs (a comment on the route
declaration in `routes.tsx` calls this out explicitly).

**Exports:** `PasswordResetConfirmPage`.

**Form / data.** `zod` schema with a cross-field `.refine`:
- `newPassword` (min 10) + `confirm`, refined so `newPassword === confirm` (error attached to
  `confirm`).
- *Gotcha:* the client min-length is **10**, but the authoritative `PasswordPolicy` on the backend
  requires **12** plus character-class rules — so a 10–11-char password passes client validation
  and is rejected server-side. The server message is surfaced verbatim via `apiErrorMessage`.

**Key behavior.** `token` is read from `useSearchParams`. On submit: if `token` is missing, set
`error = 'Missing reset token'` and stop; otherwise call `auth.confirmPasswordReset(token,
newPassword)` (`POST /api/v1/auth/password-reset/confirm`) and on success
`navigate('/login', { replace: true })` (the backend has already bumped the user's token-version,
revoking all sessions). Errors render inline.

**Aurora Glass / motion.** Same centered glass `<Card>` shell as the request page, entering with
`fadeRise`; inline error banner uses the fast fade variant; "Back to sign in" link to `/login`.

**Accessibility.** Two labeled password fields with `type="password"`; error banner `role="alert"`.

---

### `DashboardPage.tsx`
**Route `/` (protected).** The post-login landing: a 4-up grid of KPI cards, a 6-month license
issuance area chart (recharts), and a quick-actions panel. Greets the user by `fullName`.

**Exports:** `DashboardPage`. Internal helpers: `Stat` (KPI card), `ChartTooltip` (custom glass
tooltip), the `ICON` map, and the `QUICK_ACTIONS` list.

**Queries.**

| Query key | Source | Notes |
|---|---|---|
| `['orgs']` | `orgs.list` | Drives the Organizations KPI and seeds the license aggregation |
| `['plans']` | `plans.list` | Drives the Plans KPI |
| `['dashboard','licenses']` | custom async fan-out | `enabled: !!orgsQ.data`; aggregates licenses client-side |

**The license-aggregation gotcha (tenant-leak fix).** There is **no unscoped global license list**
anymore — the `/licenses` endpoint is subscription-scoped (the tenant-isolation IDOR remediation).
So the dashboard fans out client-side: for each org → `subscriptions.listForOrg(org.id)` (in
parallel, each `.catch(() => [])`), flatten, then for each subscription →
`licenses.listForSubscription(s.id)` (again parallel, fault-tolerant), flatten. The code comment
flags that a backend aggregate endpoint would be cleaner — this is an **N×M request fan-out** that
scales with the tenant count.

**Derived metrics (memoized over `licensesQ.data`):**
- `issuedByMonth` — bucket licenses by `YYYY-MM` of `issuedAt`, sort, take the **last 6 months**,
  shape `{ month, count }` for the chart (skips unparseable dates).
- `licensesThisMonth` — count with `issuedAt >= start-of-current-month`.
- `activeLicenses` — count where `!revokedAt && expiresAt > now`.

**What it renders.**
1. `<PageHeader>` with a "Live control panel" breadcrumb featuring a `pulse-ring` success dot.
2. **KPI grid** (`staggerContainer`): four `<Stat>` cards — Organizations, Plans, Licenses this
   month, Active licenses — each with `loading` skeletons bound to the relevant query's
   `isLoading` (license KPIs use `licensesLoading = orgsQ.isLoading || licensesQ.isLoading`).
3. **Issuance chart** (`lg:col-span-2`): recharts `ResponsiveContainer` → `AreaChart` with a
   `linearGradient` fill (`#6366f1` indigo, 0.28 → 0 opacity), hairline `CartesianGrid`, custom
   `ChartTooltip`, monotone area, no dots. An **empty state** (icon + copy) renders when there is
   no data.
4. **Quick actions** panel: four glass link tiles to `/orgs`, `/plans`, `/subscriptions/new`,
   `/keys`, each with a hover-lift and a chevron that nudges right on hover.

**Aurora Glass / motion (notable design decisions).**
- `Stat` cards deliberately use a **near-opaque solid fill with NO `backdrop-filter`**
  (`!bg-white/90 !backdrop-filter-none`). The inline comment states the design rule: cap
  overlapping `backdrop-filter` surfaces at ~2 per region — four live blur cards stacked over the
  drifting aurora mesh would be a perf/legibility problem, so blur is reserved for the larger chart
  + quick-actions panels. The `!` is needed because the project's `cn()` is plain `clsx`
  (not tailwind-merge), so utilities don't auto-dedupe.
- Each `Stat` has a low-chroma blurred accent wash (`accent` prop, e.g. `bg-indigo-300/40`) kept
  faint so text stays AA-contrast, an aurora icon chip, and `{...hoverLift}` (spring `y:-2,
  scale:1.01`).
- `ChartTooltip` is a `glass-pop` popover; it reads the series value from `payload` and pluralizes
  "license(s)".

**Accessibility.** Decorative SVGs and accent washes are `aria-hidden`; KPI values use
`tabular-nums`; quick-action tiles are real `<Link>`s with visible focus rings; loading shows
skeletons rather than layout shift.

---

### `OrgsListPage.tsx`
**Route `/orgs` (protected).** The organizations index — a searchable, paginated table with a
create-org dialog. "Each customer is an organization."

**Exports:** `OrgsListPage`.

**Query / mutation.**
- `useQuery(['orgs'], orgs.list)`.
- `createMut` → `orgs.create(v)` (`POST /api/v1/orgs`). On success: invalidate `['orgs']`, close
  the dialog, reset the form, toast `Organization "…" created`, and **navigate to the new org's
  detail** (`/orgs/${org.id}`). On error: toast `apiErrorMessage(e)`.

**Form.** `zod` schema `{ name (min 2), slug (min 2, /^[a-z0-9-]+$/) }`. The slug regex enforces the
URL-safe identifier shape ("Lowercase letters, digits, hyphens only").

**Client-side filtering.** Local `filter` state; `filtered` does a case-insensitive contains over
`"${name} ${slug}"`. This filters **only the already-fetched page set** (no server search param) —
fine at admin scale.

**Table columns** (`DataTable<Organization>`): Name (bold), Slug (mono `<code>` chip), Status
(`<StatusBadge status={o.status ?? 'ACTIVE'}>`), Members (`memberCount ?? '—'`), Created
(localized date). `onRowClick` navigates to the org detail; `empty` shows the API error message
when the query errored, else "No organizations yet."

**Permission gate.** The "New organization" button is wrapped in
`<PermissionGate permission="org.write">` — re-pointed from the non-enforced `org.create` to the
authority the create endpoint actually checks (finding **P3**, called out in a comment).

**Aurora Glass / motion.** Inherits the `glass-solid` `DataTable` shell; the search `<Input>` and a
`tabular-nums` count live in the table toolbar. Create uses the shared `<Dialog>` (whose
open/close motion comes from `overlayPanel`/`overlayBackdrop`). The form is submitted by id
(`form="create-org-form"`) from the dialog footer's Create button (`loading={createMut.isPending}`).

---

### `OrgDetailPage.tsx`
**Route `/orgs/:orgId` (protected).** The org workspace: a header with cross-links and a **tabbed**
body (Members / Subscriptions / SSO / API keys / Audit). The page component is a shell; each tab is
its own internal component with its own query, which keeps tab data lazy (only the active tab's
component mounts).

**Exports:** `OrgDetailPage`. Internal: `MembersTab`, `SubscriptionsTab`, `OrgAuditTab`.

**Shell.** `orgId` from `useParams`. `orgQ = useQuery(['org', orgId], () => orgs.get(orgId))`
(`enabled: !!orgId`) drives the title/slug. The header `actions` (only once `orgQ.data` is present)
link to `/orgs/:orgId/sso`, `/orgs/:orgId/api-keys`, and — gated by
`<PermissionGate permission="subscription.create">` — `/subscriptions/new?orgId=…`. `<Tabs>` is
controlled by local `tab` state; the body is wrapped in a `motion.div` **keyed on `tab`** so each
tab transition replays the `fadeRise` entrance. The SSO and API-keys tabs are lightweight stubs
that just deep-link to the dedicated full pages.

#### `MembersTab`
- **Query:** `['org', orgId, 'members']` → `orgs.members(orgId)` (`GET …/members`).
- **Mutations:**
  - `inviteMut` → `orgs.addMember(orgId, v)` (`POST …/members`); on success invalidate members,
    close dialog, reset form to `{email:'', role:'MEMBER'}`, toast "Member added".
  - `removeMut` → `orgs.removeMember(orgId, userId)` (`DELETE …/members/:userId`); on success
    invalidate + toast "Member removed".
- **Form:** `inviteSchema` `{ email, role: enum(OWNER|ADMIN|MEMBER|VIEWER) }`; role via `<Select>`.
- **Columns:** Email, Name (`displayName ?? —`), Role (`<Badge tone="info">`), Joined (date), and a
  right-aligned actions cell.
- **Permission gates:** "Add member" gated on `org.members.add`; the per-row "Remove" gated on
  `org.members.remove`. Remove also fires a native `confirm()` guard before mutating.

#### `SubscriptionsTab`
- **Query:** `['org', orgId, 'subscriptions']` → `subsApi.listForOrg(orgId)`.
- **Columns:** Plan (`planName ?? planCode ?? planId`), Status (`<StatusBadge>`), Seats, Starts,
  Ends (dates), and an "Open" link to `/subscriptions/:id`.
- **Toolbar:** count + a `subscription.create`-gated "New subscription" link carrying `?orgId=`.
- No mutations here — it is a read-only listing that defers actions to the detail page / wizard.

#### `OrgAuditTab`
- **Query:** `['org', orgId, 'audit']` → `auditApi.forOrg(orgId, { page: 1, pageSize: 50 })`. Items
  memoized from `auditQ.data?.items`.
- **Columns:** When (localized datetime), Actor (`actorEmail ?? actorId ?? —`), Action (mono code
  chip), Target (`targetType · targetId`). Read-only.

**Aurora Glass / motion.** `<Tabs>` for the segmented control; the tab body re-animates via the
`key={tab}` + `fadeRise` trick; tables are the shared `DataTable`; mutations route through `<Dialog>`
+ toasts. Badges/StatusBadges carry the semantic glass tones.

**Accessibility.** `<Tabs>` provides the tablist semantics; each table's empty/error state is
explicit; destructive remove is confirm-guarded.

---

### `PlansListPage.tsx`
**Route `/plans` (protected).** The plan catalog index — table + create dialog, structurally a twin
of `OrgsListPage`. "Each plan grants a permission set and feature flags to issued licenses."

**Exports:** `PlansListPage`.

**Query / mutation.**
- `useQuery(['plans'], plans.list)`.
- `createMut` → `plans.create({ ...v, permissions: [], features: {} })` (`POST /api/v1/plans`). New
  plans are created **empty** (no permissions/features) and the user is sent straight to the editor:
  on success invalidate `['plans']`, close dialog, reset form, toast, and
  `navigate(/plans/${p.id}/edit)`.

**Form.** `zod` `{ name (min 2), code (min 2, /^[a-z0-9-_]+$/), description? }`. Note the code regex
also allows underscores (plans) vs. orgs' hyphen-only slug.

**Columns** (`DataTable<Plan>`): Name, Code (mono chip), Status (derived:
`p.active === false ? 'RETIRED' : 'ACTIVE'` — the backend exposes `active`, not a status string),
Permissions count (`permissions?.length`), Features count (`Object.keys(features).length`), and a
right-aligned "Edit" ghost button. `onRowClick` also routes to the editor.

**Permission gate.** "New plan" gated on `plan.write` (the enforced authority; finding **P3**).

**Aurora Glass / motion.** The table is wrapped in a `fadeRise` `motion.div`; same `<Dialog>` create
pattern as orgs.

---

### `PlanEditPage.tsx`
**Route `/plans/:planId/edit` (protected).** The deep plan editor — three independently-saved
cards: **Details**, **Entitlement permissions** (a grouped checkbox picker), and **Features and
quotas** (a typed key/value editor). This is the most stateful page in this set.

**Exports:** `PlanEditPage`. Helpers: `inferType`, `newFeatureId`; type `EditableFeature`.

**Queries.**
- `['plan', planId]` → `plans.get(planId)` — the plan being edited.
- `['rbac','permissions']` → `rbac.permissions()` — the full permission catalog for the picker.

**Local state seeded from the plan (`useEffect` on `planQ.data`):**
- `form` (`react-hook-form`, `metaSchema` = `{ name, code, description?, defaultTtlDays?
  (coerced positive int) }`) reset from the plan.
- `selectedPerms: Set<string>` initialized from `plan.permissions`.
- `features: EditableFeature[]` derived from the plan's `features` **object** (`Map<String,Object>`,
  not an array) via `Object.entries`, each row given a stable `id` (`crypto.randomUUID` with
  fallback) used purely for React keys / `AnimatePresence` — the id is **never** sent to the server.
  Each row's `type` is inferred (`boolean`/`number`/`string`) from the JSON value.

**Three mutations (independent saves so one section can be saved without the others):**

| Mutation | Endpoint | Payload subtlety |
|---|---|---|
| `saveMeta` | `plans.update(planId, …)` (`PATCH /plans/:id`) | Sends only `name`/`description`/`defaultTtlDays`. **`code` is intentionally NOT sent** — plan code is immutable post-creation (the field is shown but read-back from the plan). |
| `savePerms` | `plans.setPermissions(planId, [...].sort())` (`POST /plans/:id/permissions`) | API field is **`permissionCodes`** — sending `permissions` would bind null and wipe every entitlement (finding **P0-2**, guarded in `api.ts`). |
| `saveFeatures` | `plans.setFeatures(planId, obj)` (`POST /plans/:id/features`) | Rebuilds a **JSON object keyed by trimmed feature key** (blank keys skipped); coerces values by `type` (boolean/number) before sending. |

Each mutation invalidates `['plan', planId]` and toasts on success/error.

**Permissions picker behavior.** `grouped` (memoized) buckets the permission catalog by
`category ?? code.split('.')[0]`, sorted. Each permission is a clickable `<label>` checkbox; clicks
toggle membership in `selectedPerms` via `togglePerm`. A live footer shows the selected count and up
to 12 `<Badge>` chips (`+N more` beyond that).

**Features editor behavior.** `addFeature` appends a default row (`type:'boolean', value:true`);
`updateFeature(i, patch)` patches a row by index; `removeFeature(i)` drops it. Changing a row's
**type** resets its value to a type-appropriate default (`true`/`0`/`''`). The value control swaps
by type: boolean → true/false `<Select>`, number → numeric `<Input>`, string → text `<Input>`.

**Loading/error.** Shows `<PageLoader>` while `planQ.isLoading`; a danger banner if `planQ.isError`.
The permissions card has its own nested loading/error/empty branches bound to `permsQ`.

**Aurora Glass / motion.**
- The three cards are `fadeRise` children of a `staggerContainer`.
- The feature rows use `<AnimatePresence initial={false}>` + per-row `layout` animation with a
  `SPRING.gentle` transition and an enter/exit `y:-6` fade — rows slide/settle as they're
  added/removed/reordered (this is *why* each row needs the stable `f.id` key).
- Selected permission tiles get a gradient/ring treatment (`from-indigo-50 to-violet-50 ring-1
  ring-indigo-500/10`) and a subtle `hover:-translate-y-px` lift; checkboxes use `accent-indigo-600`.

**Accessibility.** Every field/checkbox/select is id-labeled (`f-key-${i}` etc.); the empty-features
state has an explanatory call-to-action; decorative plus-icon is `aria-hidden`.

---

### `SubscriptionCreateWizard.tsx`
**Route `/subscriptions/new` (protected; typically entered with `?orgId=`).** A 5-step provisioning
wizard — **Organization → Plan → Dates and seats → Overrides → Confirm** — that creates a
subscription and can optionally **issue + download the first license** in one flow. This is the most
flow-heavy and most safety-conscious page in the set.

**Exports:** `SubscriptionCreateWizard`. Module helpers: `stepVariants`, `todayIso`, `yearFromNow`;
type `WizardState`.

**Queries.** `['orgs']` → `orgs.list` and `['plans']` → `plans.list` populate the org dropdown and
the plan card grid. `selectedOrg`/`selectedPlan` are memoized lookups used in the Confirm summary.

**Wizard state (`WizardState`, a single `useState` object):** `orgId` (pre-filled from the `?orgId=`
search param), `planId`, `startsAt` (today), `endsAt` (one year out), `seats` (default 25),
`overrides[]`. A `useEffect` re-applies a changing `?orgId=` param. `step` and `direction` drive the
stepper; `goNext`/`goBack` set `direction` (±1) then move `step` (clamped at 0).

**Per-step gating (`canNext`).** Step 0 requires `orgId`; step 1 requires `planId`; step 2 requires
both dates + `seats > 0`; steps 3 and 4 are always passable (overrides optional).

**Step bodies.**
- **0 Organization:** `<Select>` of `name (slug)`; `<PageLoader>` while orgs load.
- **1 Plan:** a card grid of selectable plan tiles (`aria-pressed`), each showing name, status
  badge, code, description, and permission/feature counts; the selected tile gets a check badge and
  gradient ring.
- **2 Dates and seats:** two `type="date"` inputs + a `type="number"` seats input clamped to
  `>= 1`.
- **3 Overrides (optional):** add/update/remove rows of `{ type, key, value }` where
  `type ∈ PERMISSION_ADD | PERMISSION_REMOVE | FEATURE_SET`. The **value** input is disabled unless
  the type is `FEATURE_SET` (the two permission ops only need a key). Empty state explains plan
  entitlements are used as-is.
- **4 Confirm:** a `<dl>` summary (org, plan, term, seats, overrides list).

**Submit flow & the double-submit / idempotency guard (the load-bearing part).**
- A single `submitting` flag stays `true` for the **entire** create(+issue+download) sequence so the
  action buttons never re-enable mid-flight — the comment ties this directly to finding **P2**: a
  mid-flight re-enable is exactly what lets a double-click create a duplicate subscription.
- `idempotencyKey = useRef(newIdempotencyKey())` holds **one key per submission attempt**. It is
  sent as the `Idempotency-Key` header so a retried/duplicate POST is collapsed server-side. The
  license issue reuses the same key with a `:license` suffix (`idempotencyKey.current + ':license'`).
- `onCreate(issue)` early-returns if already `submitting`, sets `submitting=true`, then
  `subscriptions.create(orgId, payload, idempotencyKey.current)` (dates `.toISOString()`'d, overrides
  sent only if non-empty). If `issue`, it then `issueAndDownload(sub.id)`. Finally
  `navigate(/subscriptions/${sub.id})`.
- `issueAndDownload`: `licenses.issue(subId, undefined, key+':license')` → `licenses.download(jti)`
  → `triggerDownload(blob, filename)`. The download is wrapped in its own try/catch so a **download**
  failure toasts "License issued but download failed: …" rather than masking the successful issue.
- **Failure handling:** on a create error it toasts the message, **regenerates the idempotency key**
  (the failed POST committed no row, so a fresh attempt is safe), and re-enables the buttons by
  setting `submitting=false`. Note the **success path intentionally leaves `submitting` true** —
  navigation unmounts the page, so there is no need (or desire) to re-enable.

**Aurora Glass / motion.**
- A custom **stepper** (`<nav aria-label="Progress"><ol>`): each step node is a `motion.span` that
  springs to `scale:1.06` when active; completed steps show a check on `bg-success-500`, the active
  step is `bg-aurora-primary shadow-glow`, and the connector lines animate `scaleX 0→1`
  (`DURATION.moderate`) as steps complete.
- Step content transitions with `<AnimatePresence mode="wait">` + `stepVariants`, a
  **direction-aware** slide (`x: ±24`) keyed on `direction` (advancing slides in from the right,
  going back from the left) — transform+opacity only, durations kept in the interactive band so the
  Next/Back controls never feel blocked.
- Plan tiles and override rows are glass surfaces with hover-lift and focus rings; the active plan
  tile uses the indigo→violet gradient ring.

**Accessibility.** `aria-label="Progress"` on the stepper with `aria-current="step"` on the active
node; decorative check/number glyphs `aria-hidden`; plan tiles are real `<button aria-pressed>`;
every input is id-labeled; required date/seats fields marked `required`; disabled override value
input communicates its inapplicability.

---

## Cross-cutting notes for these pages

- **Cookie-only auth, everywhere.** None of these pages reads/writes a JWT; auth rides the HttpOnly
  `cp_session` cookie via `http`'s `withCredentials`. A `401` anywhere triggers the global
  `setUnauthorizedHandler` (wired in `AuthProvider`) which clears identity and bounces to `/login`.
- **Permission gates are cosmetic.** `<PermissionGate>` only hides UI. The backend `@PreAuthorize`
  is the real check; the **P3** remediation aligned the gate codes (`org.write`, `plan.write`,
  `subscription.create`, `org.members.add/remove`) with the *enforced* authorities. Super-admins
  bypass all gates (`hasPermission` returns true on `superAdmin` / `SUPER_ADMIN` / `*`).
- **API shape-tolerance.** Every list call (`orgs.list`, `plans.list`, `subscriptions.listForOrg`,
  …) accepts either a bare array or a `Paged<T>` and normalizes to `T[]`, so pages can treat results
  uniformly.
- **Idempotency on creates.** Org/plan/subscription/license creates send an `Idempotency-Key`
  header (auto-generated unless supplied). The wizard is the one that *manages the key explicitly*
  across a multi-call sequence (P2).
- **Tenant-scoped licenses.** The dashboard's client-side license fan-out exists because the global
  unscoped license list was removed in the tenant-isolation fix; any future "all licenses" view must
  iterate subscriptions or wait for a backend aggregate endpoint.
- **Motion discipline.** Pages compose the shared `fadeRise`/`staggerContainer`/`hoverLift` variants
  and the `DURATION`/`EASE`/`SPRING` tokens; bespoke motion (login orbs, wizard stepper, feature-row
  `AnimatePresence`) still obeys transform/opacity-only and inherits global reduced-motion handling.
