# `admin-ui` — The Aurora Glass Admin SPA (Frontend Hub)

This is the **entry point to the control-panel frontend**. It tells you what the `admin-ui`
is, how to run it, how its source tree is laid out, where to read the in-depth section docs, the
full route → page → permission map, and how to extend the UI without breaking the design system.
The deep, file-by-file documentation lives in the five section docs under
[`docs/frontend/`](frontend/) — this page is the welcoming map that ties them together.

> House-style note: the section docs match the depth and tone of the backend module docs (see
> [`docs/backend/auth.md`](backend/auth.md)). When a detail here is summarized, the section doc has
> the granular version; when in doubt, the **code** (`admin-ui/src/**`, `tailwind.config.ts`,
> `index.css`, `styles/motion.ts`) is authoritative.

---

## What it is

`admin-ui` is the **human-facing console** for the control panel — the React Single-Page App an
operator uses to manage organizations, users, plans, subscriptions, licenses, signing keys, MFA/SSO,
API keys, usage analytics and the audit trail. It is a **separate npm project** (not a Maven
module); the Spring Boot backend documented under [`docs/backend/`](backend/) is the API it talks to,
and the **offline-verifiable Ed25519 `.lic` license** path it manages is entirely backend-side. The
SPA's whole job is to be a safe, fast, accessible front door to that admin/API surface.

**The stack:**

| Concern | Choice | Notes |
|---|---|---|
| UI framework | **React 18** (function components + hooks) | automatic JSX runtime (`react-jsx`), no `import React` for JSX |
| Build / dev server | **Vite 5** | Fast Refresh dev server, content-hashed production bundle to `dist/` |
| Language | **TypeScript 5.6** (strict) | project-references build (`tsc -b`); `lint` *is* the typecheck |
| Styling | **Tailwind CSS 3.4** + PostCSS/Autoprefixer | token-driven; no UI-component CSS framework — the design system is hand-built |
| Server state | **TanStack Query v5** (`@tanstack/react-query`) | `useQuery`/`useMutation`, one shared `queryClient` with status-aware retry + 30s `staleTime` |
| HTTP | **axios** | one `http` instance, `withCredentials: true`, cookie auth — no Bearer token in JS |
| Routing | **react-router-dom v6** | `BrowserRouter` (HTML5 history; nginx SPA-fallback in prod) |
| Forms | **react-hook-form** + **zod** (`@hookform/resolvers`) | schema-validated forms wired through the `<Field>` primitive |
| Animation | **framer-motion v11** | the *only* animation lib; transform/opacity-only, reduced-motion-safe |
| Charts | **recharts v2** | dashboard issuance trend + usage analytics (themed, not animated) |
| Fonts | **`@fontsource-variable/inter` + `/sora`** | self-hosted variable fonts (keeps the CSP to `'self'`) |
| Tests | **Vitest** | contract/unit tests pinning the load-bearing API + auth shapes |

> **No new deps rule.** The only additions beyond the contract stack are `framer-motion` and the
> two `@fontsource-variable/*` packages. No other chart/animation library may be added.

### The design language: Aurora Glass

The look is **Aurora Glass** — a luminous, premium, **light-mode-only** aesthetic. Frosted-glass
panels (translucent white fills with `backdrop-filter: blur() saturate()`, hairline rings, layered
Stripe/Linear-style soft shadows) float over a soft, slowly-drifting **aurora gradient-mesh**
backdrop in indigo → violet → cyan. Typography is **Inter** (UI) and **Sora** (display); the brand
accent is an indigo→violet gradient (`bg-aurora-primary`). Motion is deliberately restrained —
transform + opacity only, ≤220ms — reserved for first paint and navigation. Crucially, the system is
**accessibility-first**: dark `ink` text clears WCAG AA against the busiest region of the mesh, and
three independent gates (`@supports not (backdrop-filter)`, `prefers-reduced-transparency`,
`prefers-reduced-motion`, plus framer's `<MotionConfig reducedMotion="user">`) degrade the glamour to
safe, solid, still surfaces. Tokens live in exactly one place each — colors/shadows/radii in
`tailwind.config.ts`, surface recipes + a11y gates in `index.css`, motion in `styles/motion.ts` — and
feature code only ever *composes* them.

---

## Run it for development

```bash
cd admin-ui
npm install
npm run dev        # → http://localhost:5173
```

- The dev server listens on **port 5173** (`vite.config.ts` → `server.port`), which is exactly the
  origin the backend's CORS allowlist (`app.cors.allowed-origins`) permits by default. `host: true`
  binds all interfaces for LAN/container access.
- It talks to the API via **`VITE_API_BASE`** (default `http://localhost:8080`). To point at a
  different backend, copy `.env.example` → `.env` and set `VITE_API_BASE`. This is a **build/env**
  value (Vite inlines `VITE_*` at build time), not a runtime setting.
- **Auth is cookie-based.** Logging in sets the backend's `HttpOnly` `cp_session` cookie; axios is
  configured with `withCredentials: true` so the cookie rides every request automatically. The SPA
  never reads, stores, or attaches a JWT (the XSS-token-theft mitigation, findings P1-16 / P2 #32).
  For this to work cross-origin in dev, the backend must send `allowCredentials(true)` with the
  explicit `:5173` origin — the two settings are a matched pair.
- You need the **backend running** (and reachable at `VITE_API_BASE`) to log in; without it the login
  call fails and the app stays on `/login`.

**Other scripts** (`package.json`):

| Script | Command | Purpose |
|---|---|---|
| `npm run dev` | `vite` | dev server with Fast Refresh on 5173 |
| `npm run build` | `tsc -b && vite build` | typecheck via project refs, then production bundle to `dist/` |
| `npm run preview` | `vite preview --host` | serve the built `dist/` locally |
| `npm run lint` | `tsc -b --noEmit` | typecheck-only (the project's "lint" is the strict TS check) |
| `npm test` | `vitest run` | one-shot contract/unit test run |

**Production / container.** A two-stage `Dockerfile` builds the SPA (`node:22-alpine`) and serves the
static `dist/` from **nginx** with SPA-fallback routing, repeated per-`location` security headers, and
a per-deployment-configurable CSP `connect-src` (`${CSP_CONNECT_SRC}`, defaulting to the build-time
`VITE_API_BASE`). See the design-system doc for the full `Dockerfile` / `nginx.conf` breakdown.

---

## Directory map (`admin-ui/src`)

```
admin-ui/
├── index.html                  # Vite shell; <div id="root"> + module script; light-mode meta
├── package.json                # admin-ui npm project (scripts, deps, "no new deps" list)
├── vite.config.ts              # dev/build/test config; @/ alias; port 5173; Vitest
├── tailwind.config.ts          # ★ TOKEN SOURCE OF TRUTH (colors, shadows, radii, blur, motion)
├── postcss.config.js           # tailwindcss → autoprefixer
├── tsconfig*.json              # project-references TS config (app vs. node tooling)
├── Dockerfile / nginx.conf     # two-stage build → static nginx serve (CSP, SPA fallback)
├── .env.example                # VITE_API_BASE=http://localhost:8080
├── DESIGN_SYSTEM.md            # in-repo agent-facing brief (HARD RULES, copy-paste recipes)
└── src/
    ├── main.tsx                # ★ composition root: provider stack + font/CSS imports
    ├── App.tsx                 # trivial seam → <AppRoutes/>
    ├── routes.tsx              # ★ the single <Routes> map (public vs. protected shell)
    ├── index.css               # ★ global stylesheet: aurora backdrop, .glass-* recipes, a11y gates
    ├── vite-env.d.ts           # Vite env typing (VITE_API_BASE)
    │
    ├── styles/
    │   └── motion.ts           # ★ canonical framer-motion tokens & variants
    │
    ├── lib/                    # presentation-free core (data / auth / utils)
    │   ├── api.ts              # the axios client + every typed endpoint wrapper
    │   ├── api.test.ts         # contract tests pinning load-bearing payload shapes
    │   ├── types.ts            # TypeScript mirror of backend DTOs/enums
    │   ├── auth.tsx            # AuthProvider / useAuth (cookie-based identity)
    │   ├── auth-contract.test.ts
    │   ├── queryClient.ts      # singleton TanStack QueryClient (retry/staleTime policy)
    │   ├── download.ts         # blob → file save helper (triggerDownload)
    │   ├── motion.ts           # convenience re-export barrel (+ toastSlide)
    │   ├── cn.ts               # clsx className joiner
    │   └── toast.tsx           # global toast context/provider (chrome that lives in lib/)
    │
    ├── components/             # app chrome (stateful, app-aware composites)
    │   ├── AppShell.tsx        # sidebar/topbar layout + mobile nav drawer + route transition
    │   ├── PageHeader.tsx      # per-page title/description/breadcrumb/actions block
    │   ├── DataTable.tsx       # generic client-paginated, loading/empty-aware table
    │   ├── ErrorBoundary.tsx   # white-screen guard (the only class component)
    │   ├── ProtectedRoute.tsx  # route-level auth gate (waits for /me bootstrap)
    │   ├── PermissionGate.tsx  # element-level permission gate (affordance only)
    │   └── ui/                 # the Aurora Glass primitive kit
    │       ├── index.ts        # barrel: import { Button, Dialog, Field, … } from '@/components/ui'
    │       ├── Button.tsx      # variants/sizes, loading spinner, focus ring
    │       ├── Input.tsx       # Input/Textarea/Label/FieldError/Field (a11y-wired forms)
    │       ├── Select.tsx      # glass select with custom chevron
    │       ├── Badge.tsx       # Badge + StatusBadge (status→tone mapping)
    │       ├── Card.tsx        # Card/CardHeader/CardBody/CardFooter glass panels
    │       ├── Dialog.tsx      # focus-trapped, restore-focus modal
    │       ├── Tabs.tsx        # WAI-ARIA roving-tabindex tabs w/ sliding indicator
    │       ├── Table.tsx       # low-level semantic table parts + EmptyState
    │       └── Spinner.tsx     # Spinner + PageLoader
    │
    └── pages/                  # route leaf components (one per screen)
        ├── LoginPage.tsx       PasswordResetRequestPage.tsx  PasswordResetConfirmPage.tsx
        ├── DashboardPage.tsx
        ├── OrgsListPage.tsx    OrgDetailPage.tsx
        ├── PlansListPage.tsx   PlanEditPage.tsx
        ├── SubscriptionCreateWizard.tsx  SubscriptionDetailPage.tsx  UsagePage.tsx
        ├── LicensesPage.tsx    KeysPage.tsx
        ├── ApiKeysPage.tsx     SsoConfigPage.tsx
        └── AuditPage.tsx
```

**Dependency direction is strictly one-way:**

```
pages/*  ──compose──►  components/* (chrome)  ──compose──►  components/ui/* (primitives)
   │                        │                                        │
   └─ data via @/lib/api    └─ auth via @/lib/auth (useAuth)         └─ styling via @/lib/cn
      (@tanstack/query)                                                 + motion via @/lib/motion
```

Primitives import only `@/lib/cn` (+ framer for the two animated ones). Nothing in the kit fetches
data — the **page** owns the `useQuery` and hands `DataTable` already-resolved `rows`. The `@/` path
alias resolves to `src/` and is kept in lockstep between `vite.config.ts` and `tsconfig.app.json`.

---

## Section docs (read these for depth)

This hub is intentionally a map. Each area has a full, file-by-file deep-dive:

| Doc | Covers |
|---|---|
| [`frontend/design-system.md`](frontend/design-system.md) | **Aurora Glass foundation** — every token in `tailwind.config.ts`, the `index.css` glass recipes + aurora backdrop + three accessibility gates + focus rings, the `main.tsx` provider stack & font wiring, the motion vocabulary (`styles/motion.ts` + `lib/motion.ts`), and all build/TS/container tooling (`vite.config.ts`, tsconfigs, `Dockerfile`, `nginx.conf`, `package.json`, `.env`). |
| [`frontend/components.md`](frontend/components.md) | **Shared component kit & app chrome** — every `ui/` primitive (Button, Input/Field family, Select, Badge/StatusBadge, Spinner/PageLoader, Card family, Dialog, Tabs, Table family), the chrome (`AppShell` + mobile drawer, `PageHeader`, `DataTable`, `ErrorBoundary`, `PermissionGate`, `ProtectedRoute`), plus `lib/toast.tsx` and `lib/cn.ts`. |
| [`frontend/data-layer.md`](frontend/data-layer.md) | **Data / auth / query core (`src/lib`)** — the axios `http` client and every typed endpoint wrapper (`api.ts`), the DTO/domain contract (`types.ts`), the cookie-based `AuthProvider`/`useAuth` (`auth.tsx`), the `queryClient` retry/cache policy, the `download.ts` blob helper, and the Vite env typing. |
| [`frontend/pages-core.md`](frontend/pages-core.md) | **Core pages** — the three auth screens (`LoginPage`, password-reset request/confirm), `DashboardPage`, and the catalog/CRUD flow (`OrgsListPage`, `OrgDetailPage`, `PlansListPage`, `PlanEditPage`, `SubscriptionCreateWizard`). |
| [`frontend/pages-ops.md`](frontend/pages-ops.md) | **Operations & detail pages** — `SubscriptionDetailPage`, `UsagePage`, `LicensesPage`, `KeysPage`, `ApiKeysPage`, `SsoConfigPage`, `AuditPage`, plus `App.tsx` and the `routes.tsx` route map. |

Related backend reading: the SPA is the browser face of [`docs/backend/auth.md`](backend/auth.md)
(sessions/cookie/login) and the orgs/plans/subscriptions/licenses/keys/sso controllers. The in-repo
[`admin-ui/DESIGN_SYSTEM.md`](../admin-ui/DESIGN_SYSTEM.md) is the agent-facing brief (HARD RULES +
copy-paste recipes) that the `design-system.md` doc is the reference companion to.

---

## Route table

Routing is declared in `src/routes.tsx`. The app is **two-tier**: a handful of **public** auth routes,
and everything else nested under `<ProtectedRoute><AppShell/></ProtectedRoute>` (a layout route — the
guard waits for the cookie `/me` bootstrap to settle, then renders the chrome + an `<Outlet>`). The
catch-all `*` redirects to `/` (which, if unauthenticated, the guard bounces to `/login`).

| Path | Page | Auth | Permission gate(s) |
|---|---|---|---|
| `/login` | `LoginPage` | public | — (password → optional MFA TOTP; surfaces SSO callback errors) |
| `/password-reset/request` | `PasswordResetRequestPage` | public | — (non-enumerating "if an account exists…") |
| `/password-reset/confirm?token=…` | `PasswordResetConfirmPage` | public | — (token in `?token=`, kept out of path/referrer/logs) |
| `/` | `DashboardPage` | protected | — (read-only KPIs + issuance chart) |
| `/orgs` | `OrgsListPage` | protected | nav `org.read`; create button `org.write` |
| `/orgs/:orgId` | `OrgDetailPage` | protected | member add `org.members.add` / remove `org.members.remove`; new-sub link `subscription.create` |
| `/orgs/:orgId/sso` | `SsoConfigPage` | protected | — (save/test always shown; backend authorizes) |
| `/orgs/:orgId/api-keys` | `ApiKeysPage` | protected | create `api-key.create`; revoke `api-key.delete` |
| `/plans` | `PlansListPage` | protected | nav `plan.read`; create button `plan.write` |
| `/plans/:planId/edit` | `PlanEditPage` | protected | — (save sections; backend authorizes plan writes) |
| `/subscriptions/new` | `SubscriptionCreateWizard` | protected | reached via `subscription.create`-gated links |
| `/subscriptions/:subId` | `SubscriptionDetailPage` | protected | suspend `subscription.suspend`; cancel `subscription.cancel`; issue `license.issue`; revoke `license.revoke` |
| `/subscriptions/:subId/usage` | `UsagePage` | protected | — (read-only analytics) |
| `/licenses` | `LicensesPage` | protected | nav `license.read`; per-row revoke `license.revoke` |
| `/keys` | `KeysPage` | protected | nav `key.read`; rotate button `key.rotate` |
| `/audit` | `AuditPage` | protected | nav `audit.read` (page itself read-only) |
| `*` | → `<Navigate to="/" replace>` | — | catch-all (never a 404) |

**Two distinct gate layers — both are UI affordance only, never security:**

- **Sidebar visibility** is filtered in `AppShell` by `NAV.filter(hasPermission)`. The nav `permission`
  codes are: Dashboard (always), Organizations `org.read`, Plans `plan.read`, Licenses `license.read`,
  Signing keys `key.read`, Audit log `audit.read`.
- **Per-action visibility** is `<PermissionGate permission="…">` inside each page (the *write* codes in
  the table above, e.g. `org.write`, `key.rotate`, `license.issue`).
- **Routes themselves are not permission-guarded.** Visiting `/keys` without `key.rotate` still renders
  the read-only table — the rotate button simply doesn't appear, and the backend rejects any
  unauthorized call. `hasPermission` short-circuits to `true` for super-admins / a `SUPER_ADMIN` or
  `*` authority. The real enforcement is the backend's `@PreAuthorize` against **live** user state; a
  hidden button is a convenience, not a boundary. (During remediation, finding **P3**, the UI gate
  codes were re-pointed to the authorities the endpoints actually enforce.)

---

## Extending the UI

A short recipe for adding a screen the Aurora Glass way. The goal is that a new page looks and behaves
like every existing one with almost no bespoke CSS or motion.

**1. Add a page + route.**

- Create `src/pages/MyThingPage.tsx` exporting a named component.
- Register it in `src/routes.tsx` inside the protected `<AppShell/>` branch (or at top level if it's a
  public auth screen). If it should appear in the sidebar, add a `NAV` entry in `AppShell.tsx` with the
  appropriate `permission` read code.

**2. Compose the kit — don't reinvent it.** Follow the verbatim shape almost every list page uses:

```tsx
export function MyThingPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ queryKey: ['things'], queryFn: things.list });
  const createMut = useMutation({
    mutationFn: things.create,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['things'] }); toast.success('Created'); },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  return (
    <>
      <PageHeader
        title="Things"
        description="What this page manages."
        actions={
          <PermissionGate permission="thing.write">
            <Button onClick={/* open dialog */}>New thing</Button>
          </PermissionGate>
        }
      />
      <DataTable
        rows={q.data}
        columns={columns}
        rowKey={(r) => r.id}
        loading={q.isLoading}
        empty={q.isError ? apiErrorMessage(q.error) : 'No things yet.'}
        onRowClick={(r) => navigate(`/things/${r.id}`)}
        toolbar={<Input placeholder="Search…" className="max-w-sm" />}
      />
    </>
  );
}
```

- **Data:** read with `useQuery({ queryKey, queryFn: api.x })`; write with `useMutation` whose
  `onSuccess` does `invalidateQueries` + `toast.success` and whose `onError` does
  `toast.error(apiErrorMessage(e))`. Use hierarchical array query keys (`['org', orgId, 'members']`)
  so a mutation can invalidate a precise subtree. **Never** add a Bearer token — auth is the cookie.
- **API calls:** add the typed wrapper to `src/lib/api.ts` (and its types to `types.ts`). If it's a
  create/rotate, send the `Idempotency-Key` via `idempotent(...)`. List wrappers should tolerate both a
  bare array and a `Paged<T>` envelope. Field-name fidelity is load-bearing (e.g. `setPermissions` must
  send `{ permissionCodes }`) — add a pin test in `api.test.ts` if it matters.
- **Forms:** `react-hook-form` + `zodResolver`; wrap each control in `<Field label htmlFor error>` and
  pass `invalid={!!errors.x}` to the control. `Field` wires `aria-describedby`/`aria-errormessage` for
  you. Match `Field htmlFor` to the control's `id`.
- **Modals:** use `<Dialog open onClose title footer>`; put the `<form id="x">` in the body and a
  `<Button type="submit" form="x" loading={mut.isPending}>` in the footer. `Dialog` handles focus
  trap/restore and Escape/backdrop close.
- **Tables:** pass `rows`/`columns`/`rowKey`/`loading`/`empty` to `DataTable`; surface fetch errors by
  putting `apiErrorMessage(error)` in `empty`. Don't wrap `DataTable` in a `Card` — it ships its own
  (denser, opaque) glass surface.
- **Status:** never re-map status→color ad hoc — use `<StatusBadge status={…}>`, the single source of
  that mapping.
- **Authorization:** gate every privileged control in `<PermissionGate>` using the code the **backend
  endpoint enforces** (not a vanity code). Remember it's affordance only.

**3. Follow the design tokens — never hardcode.**

- Surfaces: `Card`/`.glass-card` for translucent panels, `DataTable`/`.glass-solid` for dense data,
  the chrome classes (`.glass-nav`/`.glass-sticky`) for layout, `.glass-pop` for floating popovers.
  Don't put meaningful text on a `< 0.55` opacity surface, and cap overlapping `backdrop-filter` layers
  at ~2 per region (the dashboard's `Stat` cards deliberately use a solid fill for exactly this reason).
- Color/spacing/elevation: use the named utilities (`text-ink*`, `bg-aurora-primary`, `shadow-glass*`,
  `ring-slate-900/5`, the `2xl` card radius). The canonical card class is in `DESIGN_SYSTEM.md`.
  Remember `cn()` is plain `clsx` (not `tailwind-merge`) — extend base classes, don't fight them
  (use `!important` only when you must override, as the `Stat` cards do).
- Motion: import variants/tokens from `@/lib/motion` (`fadeRise`, `staggerContainer`, `hoverLift`,
  `DURATION`, `EASE`, `SPRING`). Animate **transform + opacity only**, never layout properties. The
  shell already wraps your page in a `pageTransition`, so a page often needs no entrance code of its
  own. All motion inherits global reduced-motion handling — you don't re-implement it.
- Accessibility is not optional: keep WCAG AA contrast, pair color with text/icon (never color alone),
  give interactive elements visible focus rings (the kit does this for you), and label inputs via
  `<Field>`. The three CSS a11y gates and framer's `MotionConfig` are global — don't bypass them.

**4. Don't touch the contract files lightly.** `lib/api.ts`, `lib/types.ts`, `lib/auth.tsx`,
`lib/queryClient.ts`, the `*.test`/`*-contract.test` files, and `routes.tsx` are the off-limits /
careful-change set called out in `DESIGN_SYSTEM.md`. Adding endpoints/types/routes is fine; changing
existing payload shapes is a backend-contract change — keep the pin tests green.

---

## Cross-cutting invariants (quick reference)

- **Cookie, not token.** Auth is the `HttpOnly cp_session` cookie + `withCredentials: true`. Every
  login path (password, MFA, SSO) converges on `auth.me()` inside `refresh()`. A `401` anywhere fires
  the global `setUnauthorizedHandler` → `clear()` → `/login`. Never reintroduce `localStorage` tokens.
- **One source per concern.** Tokens → `tailwind.config.ts`; surfaces + backdrop + a11y gates + focus
  rings → `index.css`; motion → `styles/motion.ts` (re-exported via `lib/motion.ts`). Feature code
  composes, never re-derives.
- **Light mode only.** No `dark:` variants, no theme toggle; `color-scheme: light` pinned at the
  document level.
- **Permission gates are affordances, not enforcement** — sidebar (`AppShell`) + per-action
  (`PermissionGate`). Routes are not guarded; the backend `@PreAuthorize` is the boundary.
- **Animate transform + opacity only**, ≤220ms, reserved for first paint/navigation; two reduced-motion
  nets (framer `MotionConfig` + the CSS clamp) plus the `@supports`/reduced-transparency gates ship
  together.
- **Idempotency-Key on creates**, tolerant list unwrapping, and tolerant JSON parsing
  (`decodeSsoProvider`/`parseScopes` never white-screen) are baked into `api.ts`.
- **`@/` alias** must stay in sync between `vite.config.ts` and `tsconfig.app.json`; **dev port 5173**
  matches the backend CORS default.
