# `admin-ui` — Shared Component Kit & App Chrome (Aurora Glass)

## Module overview

This is the **shared UI layer** of the control-panel admin SPA: the small set of presentational
primitives and structural "chrome" components that every feature page composes. It is deliberately
split into two tiers:

1. **`src/components/ui/**` — the primitive kit.** Unopinionated, near-stateless building blocks
   (Button, Input/Textarea/Label/Field/FieldError, Select, Badge/StatusBadge, Spinner/PageLoader,
   Card family, Dialog, Tabs, Table family). These wrap native HTML elements, forward refs and
   `...rest` props, and carry the **Aurora Glass** visual language. They have no knowledge of
   routing, data fetching, or auth. The barrel `ui/index.ts` re-exports the lot so pages can write
   `import { Button, Dialog, Field, Input } from '@/components/ui'`.

2. **`src/components/*.tsx` — the app chrome.** Stateful, app-aware composites that wire the
   primitives to the rest of the app: `AppShell` (the persistent sidebar/topbar layout + mobile nav
   drawer + animated route outlet), `PageHeader` (per-page title block), `DataTable` (the
   client-paginated, loading/empty-aware table that nearly every list page uses), `ErrorBoundary`
   (white-screen guard), and the two authorization helpers `ProtectedRoute` (route-level auth gate)
   and `PermissionGate` (element-level permission gate).

Two `lib/` helpers are in scope because the kit depends on them: **`lib/cn.ts`** (the className
joiner used by every component) and **`lib/toast.tsx`** (the toast context/provider that is
technically a "chrome" component — a global notification layer — even though it lives under `lib/`).

### Design system: "Aurora Glass"

Every primitive speaks one visual language, defined centrally and applied via className:

| Layer | Where it lives | What it provides |
|---|---|---|
| Color/shadow/radius/duration **tokens** | `tailwind.config` (e.g. `aurora-primary`, `shadow-glow`, `glass`, `ink-*`, `duration-fast`, `ease-out-quint`) | Named Tailwind utilities the components reference by name. |
| **Glass surface utilities** | `src/index.css` `@layer components` (`.glass`, `.glass-card`, `.glass-solid`, `.glass-nav`, `.glass-sticky`, `.glass-pop`, `.skeleton`, `.pulse-ring`) | Frosted backdrop-blur surfaces with baked-in shadows + accessibility fallbacks. |
| **Motion tokens & variants** | `src/styles/motion.ts` (canonical) re-exported via `src/lib/motion.ts` | `DURATION`, `EASE`, `SPRING`, `fadeRise`, `pageTransition`, `toastSlide`, etc. |
| Reduced-motion / reduced-transparency / no-`backdrop-filter` **safety nets** | `src/index.css` media/`@supports` queries + `<MotionConfig reducedMotion="user">` in `main.tsx` | Strip animation and drop blur to solid surfaces for users/agents that request it, or browsers that can't render it. |

The two **load-bearing accessibility invariants** that run through the whole kit:

- **Meaning never rests on color alone.** `Badge`/`StatusBadge` pair a tinted fill with a text
  label and an inset ring; toasts pair a semantic color with a per-kind icon and an `sr-only`
  prefix ("Success: …").
- **Animation is transform/opacity only and respects reduced-motion.** Components use
  `motion-safe:` Tailwind variants or framer-motion (which obeys the global `MotionConfig`), and the
  `index.css` net is the belt-and-braces fallback. No component animates layout properties
  (width/height/top/left), which would thrash over the glass blur.

### How it fits the bigger picture

The control panel backend (documented in `docs/backend/*`) secures the admin/API surface; this kit
is the **browser face** of that surface. The dependency direction is strictly:

```
pages/*  ──compose──►  components/* (chrome)  ──compose──►  components/ui/* (primitives)
   │                        │                                       │
   └── data via @/lib/api (react-query)        auth via @/lib/auth (useAuth)     styling via @/lib/cn
```

Primitives import **only** `@/lib/cn` (and, for the two animated ones, `framer-motion` +
`@/styles/motion`). Chrome components additionally import `@/lib/auth` (`useAuth`),
`react-router-dom`, and the primitives. Pages bring the data layer (`@tanstack/react-query` +
`@/lib/api`), forms (`react-hook-form` + `zod`), and toasts (`@/lib/toast`). Nothing in the kit
fetches data itself — `DataTable` receives already-fetched `rows`; the page owns the `useQuery`.

---

## The big picture: how a list page composes the kit

Almost every feature page follows the same recipe (verbatim shape from `OrgsListPage.tsx`):

```
ProtectedRoute                       ← route gate: waits for /me bootstrap, redirects to /login
  └─ AppShell (layout, via <Outlet/>)← sidebar + topbar + animated page transition
       └─ <PageHeader title=… actions={<PermissionGate><Button/></PermissionGate>} />
       └─ <DataTable rows={query.data} columns=… loading=… empty=… onRowClick=… toolbar={<Input/>} />
       └─ <Dialog open=… onClose=…>   ← create/edit modal
            └─ <form> <Field><Input invalid=…/></Field> … </form>
```

- **Data**: the page runs `useQuery({ queryKey, queryFn: api.x })`; passes `query.data` →
  `DataTable rows`, `query.isLoading` → `loading`, and `apiErrorMessage(query.error)` → `empty`.
- **Mutations**: `useMutation` with `onSuccess` → `qc.invalidateQueries(...)` + `toast.success(...)`
  and `onError` → `toast.error(apiErrorMessage(e))`. The `Button loading={mut.isPending}` shows the
  inline spinner during the round-trip.
- **Forms**: `react-hook-form` + `zodResolver`; each control is wrapped in `<Field>` (which wires
  `aria-describedby`/`aria-errormessage`) and gets `invalid={!!errors.x}` for the rose treatment.
- **Authorization**: page-level access is enforced by `ProtectedRoute`; per-action visibility by
  `PermissionGate` (and the sidebar self-filters via `hasPermission`). Note this is **UI affordance
  only** — the backend re-enforces every authority (the docs and code call out finding **P3**, where
  UI gate codes had diverged from the authorities the endpoint actually checks).

---

## `src/lib/cn.ts` — className joiner

The smallest, most-used file in the kit. A one-line wrapper over `clsx`:

```ts
export function cn(...inputs: ClassValue[]) {
  return clsx(inputs);
}
```

**Responsibility.** Merge conditional className fragments into one string, accepting the full `clsx`
vocabulary (strings, arrays, `{ 'class': boolean }` objects, falsy skips). Every primitive uses it
in the pattern `cn(baseClasses, variantClasses[variant], className)` so a caller's `className` is
**appended last** and can override/extend the defaults. **Note:** this is plain `clsx`, *not*
`tailwind-merge` — later conflicting Tailwind utilities are not de-duplicated, they simply both
emit and CSS source-order wins. Callers therefore extend (e.g. `className="max-w-sm"`) rather than
fight base utilities.

---

## The primitive kit (`src/components/ui/`)

### `ui/index.ts` — barrel

`export * from` each primitive module (`Button`, `Card`, `Input`, `Select`, `Badge`, `Table`,
`Dialog`, `Tabs`, `Spinner`). Pages import the whole kit from `@/components/ui`. (`DataTable`,
`PageHeader`, the gates, `ErrorBoundary`, and `AppShell` are *not* in this barrel — they live one
level up under `@/components/*` because they are chrome, not primitives.)

---

### `ui/Button.tsx`

**`Button`** — `forwardRef<HTMLButtonElement, ButtonProps>`. The single action primitive; wraps a
native `<button>` and forwards the ref + all `ButtonHTMLAttributes` via `...rest`.

**Props (`ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>`):**

| Prop | Type | Default | Effect |
|---|---|---|---|
| `variant` | `'primary' \| 'secondary' \| 'danger' \| 'ghost' \| 'outline'` | `'primary'` | Picks the surface treatment from `variantClasses`. |
| `size` | `'sm' \| 'md' \| 'lg'` | `'md'` | Padding/text-size from `sizeClasses` (`sm` px-2.5/text-xs → `lg` px-5/text-base). |
| `loading` | `boolean` | — | Renders a leading inline spinner **and** disables the button. |
| `disabled` | `boolean` | — | Native disabled (also implied by `loading`). |
| `className`, `children`, …native | | | Forwarded; `className` appends last. |

**Variants (Aurora Glass):**

- `primary` — `bg-aurora-primary` gradient + `shadow-glow`, hover deepens gradient + lifts
  `-translate-y-px`; disabled flattens to `bg-brand-300`, no glow, no lift.
- `secondary` / `outline` — identical: frosted glass chip (`bg-white/60 backdrop-blur`, white border,
  `shadow-glass-sm`) that brightens to `bg-white/80` and lifts on hover.
- `ghost` — transparent, gains a `bg-white/60` wash on hover; no lift.
- `danger` — `bg-danger-600` + a rose drop-shadow; hover darkens + lifts.

**Key behavior.** `disabled={disabled || loading}` — a loading button cannot be re-clicked. The
spinner is a CSS-only `animate-spin` border ring sized `h-3 w-3` using `border-current` so it
inherits the button text color (white on primary/danger, ink on glass variants), with
`border-t-transparent` cutting the gap. All micro-interactions are CSS transitions
(`transition-all duration-fast ease-out-quint`, ≤150ms) so `prefers-reduced-motion` neutralizes
them via the global net.

**Accessibility.** `focus-visible:ring-2 ring-indigo-500 ring-offset-2` gives a clear keyboard
focus ring on every variant; `disabled:cursor-not-allowed disabled:opacity-70`. It stays a real
`<button>` so type/`form`/`onClick`/aria pass straight through (pages use `type="submit"
form="…"` to submit a `<form>` that lives in a Dialog body — see Dialog/OrgsListPage).

---

### `ui/Input.tsx` — Input, Textarea, Label, FieldError, Field

The form-field family. One shared visual base (`fieldBase`) plus valid/invalid border+ring variants,
shared by `Input`, `Textarea`, and `Select`.

**`Input`** — `forwardRef<HTMLInputElement, InputProps>`, wraps `<input>`.
**`Textarea`** — `forwardRef<HTMLTextAreaElement, TextareaProps>`, wraps `<textarea>`.

Both add a single extra prop:

| Prop | Type | Effect |
|---|---|---|
| `invalid` | `boolean` | Swaps `fieldValid` (slate border, indigo focus ring) → `fieldInvalid` (rose border + rose focus ring) **and** sets `aria-invalid={true \| undefined}` (omitted when false, never `aria-invalid="false"`). |

**Styling.** `fieldBase` = `bg-white/70` frosted glass at rest that crisps to solid `focus:bg-white`
with a `focus:ring-2` indigo (or rose) ring; `transition-colors duration-fast`, placeholder
`text-ink-faint`. The same `fieldBase` string is duplicated in `Select.tsx`, keeping the three
controls visually identical.

**`Label({ htmlFor, children, required, hint })`** — a `<label>` row. Renders the label text;
appends a rose `*` when `required`; optional right-aligned `hint` node (e.g. a char counter). Pairs
with a control via `htmlFor`.

**`FieldError({ id, children })`** — a `<p class="text-danger-600">`. **Returns `null` when there
is no `children`**, so callers can pass `errors.x?.message` unconditionally and nothing renders when
valid. The `id` lets a control point at it.

**`Field({ label, htmlFor, required, hint, error, children })`** — the composition wrapper that ties
the three together and does the **a11y wiring** pages rely on. Behavior:
- Renders `<Label>` (with `required`/`hint`), then the control (`children`), then `<FieldError>`.
- Computes `errorId = ${htmlFor}-error` **only when** both an `error` and an `htmlFor` exist.
- If there is an error and the single child is a valid React element, it **clones the child** to
  inject `aria-describedby` (merged with any existing value the child already had, space-joined and
  de-duplicated of falsy) **and** `aria-errormessage`, both pointing at `errorId`. This is why a
  page only writes `<Field error=…><Input id=… invalid=…/></Field>` and gets full screen-reader
  error association for free — the error text is announced as the input's description.

**Usage note.** The page is responsible for matching `Field htmlFor` to the control's `id`, and for
passing `invalid={!!errors.x}` to the control (Field sets the ARIA *association*, the control sets
the visual+`aria-invalid` *state*). See `OrgsListPage` create dialog for the canonical pattern.

---

### `ui/Select.tsx`

**`Select`** — `forwardRef<HTMLSelectElement, SelectProps>`, wraps a native `<select>` (semantics,
keyboard, and `children` `<option>`s preserved).

| Prop | Type | Effect |
|---|---|---|
| `invalid` | `boolean` | Same valid/invalid border+ring swap as Input, plus `aria-invalid`. |

**Styling.** Re-implements the Input glass base inline (rather than importing it) and adds
`appearance-none` + a **custom indigo chevron** drawn as an inline-SVG `bg-[url(...)]` data URI
pinned `bg-[right_0.6rem_center]` with `pr-9` clearance, so the native arrow is replaced by one that
matches the design system. Keeps it in the same visual family as Input/Textarea.

**Accessibility.** Native `<select>` means full keyboard + AT support out of the box; `invalid`
adds the error ring + `aria-invalid`. (Used in e.g. `OrgDetailPage`'s invite-member role picker.)

---

### `ui/Badge.tsx` — Badge, StatusBadge

**`Badge({ tone, ...rest })`** — a `<span>` pill. `HTMLAttributes<HTMLSpanElement>` plus:

| Prop | Type | Default |
|---|---|---|
| `tone` | `'neutral' \| 'success' \| 'warning' \| 'danger' \| 'info'` | `'neutral'` |

Each tone (`toneClasses`) pairs a soft tinted fill with an **AA-readable** text color and an
`ring-1 ring-inset`, so meaning is conveyed by shape+ring+label, not hue alone. `info` maps to the
indigo **brand** accent (`bg-brand-50/90 text-brand-700`). Renders `...rest` (so `children` is the
label and any extra props/`className` pass through).

**`StatusBadge({ status })`** — domain convenience over `Badge`. Returns **`null` for an empty
status**; otherwise uppercases the string and maps it to a tone:

| Status (uppercased) | Tone |
|---|---|
| `ACTIVE` | success |
| `SUSPENDED`, `EXPIRED`, `PENDING` | warning |
| `CANCELLED`, `DISABLED`, `REVOKED`, `RETIRED` | danger |
| `DRAFT`, `INVITED` | info |
| anything else | neutral |

Renders the uppercased status as the badge label. This is the single place status→color mapping
lives, so org/subscription/license/key/user statuses render consistently across every page (e.g.
`OrgsListPage` status column: `<StatusBadge status={o.status ?? 'ACTIVE'} />`).

---

### `ui/Spinner.tsx` — Spinner, PageLoader

**`Spinner({ size = 4 })`** — an indigo accent ring (`border-t-brand-600`) over a soft track
(`border-slate-200/80`) that `animate-spin`s. `size` is a multiplier: the rendered diameter is
`size * 0.25rem` (so `size=4` → 1rem, `size=8` → 2rem), applied as an inline `style` width/height.

**Accessibility.** `role="status"` + `aria-label="Loading"` so assistive tech announces the busy
state. Honors `prefers-reduced-motion` via the global `index.css` net.

**`PageLoader()`** — a full-section centered Spinner (`size=8`) inside an `h-64` flex box. Used as
the route-loading fallback by `ProtectedRoute` (while `/me` bootstraps) and by lazy-route
`Suspense` fallbacks.

---

### `ui/Card.tsx` — Card, CardHeader, CardBody, CardFooter

The glass surface container family for grouping content.

- **`Card`** — `<div>` with the canonical surface: `rounded-2xl`, white border,
  `bg-white/70 backdrop-blur-glass backdrop-saturate-150 shadow-glass ring-1 ring-slate-900/5`.
  Spreads `...rest` (so it accepts `className`, `id`, event handlers). The frosted, slightly
  translucent body card.
- **`CardHeader({ title, description, actions, className })`** — a flex row: an `<h3>` title
  (`text-base font-semibold text-ink`) with optional `description` (`text-ink-muted`) on the left,
  and an optional `actions` node group on the right; bottom hairline border.
- **`CardBody`** — padded (`p-5`) content `<div>`; spreads `...rest`.
- **`CardFooter`** — top hairline + faint `bg-white/40` footer band (`px-5 py-4`); spreads `...rest`.

Used by detail pages (e.g. `OrgDetailPage`) to frame sections. Cards are the "panel" unit;
`DataTable` ships its own surface so it is *not* wrapped in a Card.

---

### `ui/Dialog.tsx`

**`Dialog`** — the modal primitive. A fully self-contained, accessible modal with focus management
and a focus trap. Conditionally renders (returns `null` when `!open`) rather than animating mount.

**Props:**

| Prop | Type | Purpose |
|---|---|---|
| `open` | `boolean` | Controlled visibility (parent owns the state). |
| `onClose` | `() => void` | Called on Escape, backdrop click, or a footer Cancel button the parent wires. |
| `title` | `ReactNode?` | Renders an `<h3>` and sets `aria-labelledby`. |
| `description` | `ReactNode?` | Renders a `<p>` and sets `aria-describedby`. |
| `children` | `ReactNode?` | Body (typically a `<form>`). |
| `footer` | `ReactNode?` | Right-aligned action bar (typically Cancel + submit `Button`s). |
| `size` | `'sm' \| 'md' \| 'lg' \| 'xl'` | `max-w-sm/md/2xl/4xl` panel width. Default `'md'`. |

**Accessibility (the substantive part):**

- **Roles & names.** Panel is `role="dialog" aria-modal="true"`. `aria-labelledby`/`aria-describedby`
  are set to stable `useId()`-derived ids **only when** `title`/`description` are provided (omitted
  otherwise, never dangling).
- **Escape to close** — a window `keydown` listener (added only while `open`) calls `onClose`.
- **Backdrop click** — the `bg-slate-900/40` overlay is `aria-hidden` and its `onClick` calls
  `onClose`.
- **Focus management** — on open it records `document.activeElement` in `restoreFocusRef`, then
  moves focus to the first focusable control inside the panel (falling back to the panel itself,
  which is `tabIndex={-1}`). The effect's cleanup (on close/unmount) **restores focus to the
  trigger**, so keyboard users return to where they were.
- **Focus trap** — `onPanelKeyDown` intercepts `Tab`/`Shift+Tab`: it queries the visible, enabled
  focusable descendants (filtering by `offsetParent !== null`), and wraps focus from last→first
  (Tab) and first/panel→last (Shift+Tab). If nothing is focusable it pins focus on the panel. This
  keeps keyboard and screen-reader focus inside the modal, never reaching the obscured page.

**Motion/styling.** Backdrop `motion-safe:animate-fade-in`; panel `motion-safe:animate-scale-in`
(both CSS keyframes, reduced-motion-gated). Panel is a heavier glass surface
(`bg-white/90 backdrop-blur-glass shadow-glass-xl`) with header/body/footer bands mirroring the Card
family. Layout: `fixed inset-0 z-40` scroll container, panel `mt-12` from the top on `sm+`.

**Composition gotcha.** The submit button typically lives in `footer` while the `<form>` lives in
`children`; pages bridge them with `<form id="x">` + `<Button type="submit" form="x">` (native form
association), so the action bar can submit a body form. See `OrgsListPage`.

---

### `ui/Tabs.tsx`

**`Tabs({ tabs, active, onChange })`** — a controlled WAI-ARIA tablist with a framer-motion sliding
active indicator. The parent owns `active` state and switches the rendered panel content itself.

| Prop | Type | Purpose |
|---|---|---|
| `tabs` | `TabDef[]` (`{ id: string; label: string }`) | The tab set. |
| `active` | `string` | Currently selected tab id (controlled). |
| `onChange` | `(id: string) => void` | Called on click or arrow-key activation. |

**Accessibility (roving-tabindex tabs pattern):**

- `<nav role="tablist" aria-label="Tabs">` containing `role="tab"` buttons.
- Each tab has `id="tab-{id}"`, `aria-controls="tabpanel-{id}"`, `aria-selected={isActive}`, and
  `aria-current="page"` when active.
- **Roving tabindex** — only the active tab has `tabIndex={0}`; the rest are `-1`, so Tab enters the
  group once and arrow keys move within it.
- **Keyboard** — `onKeyDown` handles `ArrowRight`/`ArrowDown` (next, wrapping), `ArrowLeft`/`ArrowUp`
  (previous, wrapping), `Home` (first), `End` (last). On move it `preventDefault`s, calls `onChange`
  to **activate** the tab (activation-follows-focus), and `requestAnimationFrame`s a `.focus()` on
  the newly-active button (which has just become `tabIndex 0`) via the `tabRefs` map.

> **A11y caveat for page authors:** Tabs emits `aria-controls="tabpanel-{id}"` on each tab, but it
> does **not** render the panel. The page renders the active panel's content and is responsible for
> giving that container `id="tabpanel-{activeId}"` `role="tabpanel"` `aria-labelledby="tab-{activeId}"`
> to complete the relationship. (In the current pages the panel content is rendered directly under
> `<Tabs>`; the matching `tabpanel` ids are not always present — a known gap.)

**Motion.** The active underline is a `motion.span` with a shared `layoutId="tabs-active-indicator"`,
so framer-motion animates it **sliding** from the old tab to the new one (`SPRING.gentle`), a
signature Aurora Glass touch. Inactive tabs render a transparent placeholder span to hold layout.
`bg-aurora-primary` underline, `text-indigo-700` active label. Tab strip is horizontally scrollable
(`overflow-x-auto`) for narrow viewports.

---

### `ui/Table.tsx` — Table, THead, TBody, TR, TH, TD, EmptyState

The low-level semantic table primitives. Thin styled wrappers over native table elements; all spread
`...rest` and merge `className` via `cn`. `DataTable` composes these — pages rarely use them directly.

| Export | Element | Styling / behavior |
|---|---|---|
| `Table` | `<table>` inside an `overflow-x-auto` div | `w-full text-sm`; the wrapper makes wide tables scroll horizontally on small screens. |
| `THead` | `<thead>` | `glass-sticky` (sticky, more-opaque glass so scrolled content doesn't bleed through), uppercase muted column labels. |
| `TBody` | `<tbody>` | `divide-y divide-slate-900/5` hairline row separators. |
| `TR` | `<tr>` | `transition-colors hover:bg-indigo-50/40` row hover wash. |
| `TH` | `<th>` | Left-aligned uppercase tracking-wide header cell, `px-4 py-2.5`. |
| `TD` | `<td>` | `px-4 py-2.5 text-ink-soft` data cell. |
| `EmptyState` | `<div>` | Centered muted "no results" block (`py-12`); rendered inside a full-width `<td>` by DataTable. |

---

## App chrome (`src/components/`)

### `AppShell.tsx`

**`AppShell()`** — the persistent application layout, mounted once as the parent route element; its
`<Outlet/>` renders the active page. Owns the desktop sidebar, the topbar, the **mobile nav drawer**,
and the animated route transition. This is the most stateful component in the kit.

**Data hooks / app wiring.** `useAuth()` → `{ user, logout, hasPermission }`; `useNavigate()` +
`useLocation()` from react-router. No data fetching of its own.

**Navigation model.** A static `NAV: NavItem[]` (`{ to, label, icon, permission? }`) drives both the
desktop sidebar and the mobile drawer. The list is filtered to `visibleNav` by
`!n.permission || hasPermission(n.permission)` — so a user without e.g. `audit.read` never sees the
Audit link. (This is affordance only; routes/endpoints re-enforce.) Items: Dashboard (always),
Organizations (`org.read`), Plans (`plan.read`), Licenses (`license.read`), Signing keys
(`key.read`), Audit log (`audit.read`).

**Icons.** `NAV_ICONS` maps each item's single-letter `icon` key to a decorative inline-SVG
line-icon (all `aria-hidden`, `stroke="currentColor"`), with a fallback to the letter glyph for any
unmapped key. `NavIcon` renders the glyph in a fixed `h-5 w-5` box. The label carries the meaning;
icons are purely decorative — chosen so the existing NAV contract (`to/label/permission/icon`) is
untouched.

**Desktop layout (`>= md`).** A `w-64` `<aside class="glass-nav">`: brand lockup (a `CP`
`bg-aurora-primary` chip + "Control Panel"), the primary `<nav aria-label="Primary">` of `NavLink`s,
and a footer with the user avatar (first initial in an `aurora-chip` ring), name/email, and a
"Sign out" `Button`. Each `NavLink` uses the render-prop `isActive` to apply an active treatment
(`from-indigo-50 to-violet-50` gradient pill + ring) and reveal a left **active rail** (an
`aria-hidden` `bg-aurora-primary` bar that fades in via opacity). `end={n.to === '/'}` keeps the
Dashboard link from matching every route.

**Topbar.** A `sticky top-0 z-20 glass-sticky` header. Below `md` it shows the hamburger button +
brand; on `md+` it shows the avatar/name on the right. A `md:hidden` ghost "Sign out" appears on
mobile (the drawer footer also has one).

**Mobile nav drawer (`< md`).** The substantive interactive piece:
- Toggled by the hamburger button, which carries full ARIA: `aria-expanded={mobileNavOpen}`,
  `aria-controls="mobile-nav"`, and a dynamic `aria-label` ("Open/Close navigation menu").
- State `mobileNavOpen` (`useState`). Two effects manage it: one **closes the drawer on route
  change** (`useEffect` on `location.pathname`), so following a link auto-dismisses it; another adds
  an **Escape-to-close** window listener while open.
- Rendered inside `<AnimatePresence>`: a `motion.div` backdrop (`fixed inset-0 z-30 bg-slate-900/40`,
  fade in/out, click-to-close, `aria-hidden`) and a `motion.div` panel (`id="mobile-nav"`,
  `role="dialog" aria-modal="true" aria-label="Navigation"`) that **slides in from the left**
  (`x: '-100%' → 0`, spring `stiffness 400 / damping 38`). The panel repeats the brand + a close (×)
  button + the **same `visibleNav`** links (each with `onClick` to close) + the user footer.
- The drawer reveals the identical links below `md`; the `<aside>` handles `>= md`. The mobile and
  desktop nav share `visibleNav`, so permission filtering is consistent.

**Route transition.** `<main>` wraps the `<Outlet/>` in `<AnimatePresence mode="wait"
initial={false}>` with a `motion.div keyed on location.pathname` using the `pageTransition` variant
(fade + small `y` slide, `EASE.emphasized`). `mode="wait"` lets the old page exit before the new one
enters; `initial={false}` skips the animation on first paint.

**Logout.** `onLogout` awaits `logout()` (clears the session server- and client-side) then
`navigate('/login', { replace: true })`.

**Accessibility summary.** Two labeled `<nav>`s ("Primary"), an ARIA-correct disclosure button for
the drawer, a `role="dialog"` modal drawer, Escape + route-change + backdrop dismissal, and
keyboard-visible focus rings on the icon buttons (`focus-visible:ring-2`).

---

### `PageHeader.tsx`

**`PageHeader({ title, description, actions, breadcrumb })`** — the standard page title block,
rendered at the top of nearly every page (between `AppShell`'s main and the page body).

| Prop | Type | Renders |
|---|---|---|
| `title` | `ReactNode` | `<h1 class="font-display text-2xl font-semibold text-ink">` — the page's single `<h1>`. |
| `description` | `ReactNode?` | A muted subtitle `<p>`. |
| `actions` | `ReactNode?` | A right-aligned, wrapping action group (`flex flex-wrap gap-2`) — typically primary `Button`s, often wrapped in `PermissionGate`. |
| `breadcrumb` | `ReactNode?` | A small muted line above the title (e.g. a `<Link>` back to the parent list). |

**Layout/motion.** `mb-6`; entrance via `motion-safe:animate-fade-up` (CSS, reduced-motion-gated).
Responsive: title/actions stack on mobile, become a baseline-aligned row on `sm+`
(`sm:flex-row sm:items-end sm:justify-between`). Purely presentational — no state, no hooks.

---

### `DataTable.tsx`

**`DataTable<T>(props)`** — the workhorse list component nearly every list page renders. A generic,
client-side-paginated table built on the `ui/Table` primitives, with built-in loading skeletons, an
empty state, an optional toolbar, and optional keyboard-accessible row activation. It does **not**
fetch — the page passes already-resolved `rows`.

**Props (`DataTableProps<T>`):**

| Prop | Type | Purpose |
|---|---|---|
| `rows` | `T[] \| undefined` | The data (often a react-query `data`, hence `undefined`-tolerant → treated as `[]`). |
| `columns` | `Column<T>[]` | Column defs (see below). |
| `rowKey` | `(row: T) => string` | Stable React key per row. |
| `loading` | `boolean?` | Renders 5 skeleton rows instead of data. |
| `empty` | `ReactNode?` | Empty-state content (default `'No results.'`). Pages pass `apiErrorMessage(error)` here to surface fetch errors. |
| `pageSize` | `number?` | Rows per page; default `25`. |
| `onRowClick` | `(row: T) => void` | Makes rows interactive (navigate to detail). |
| `toolbar` | `ReactNode?` | Optional header bar above the table (search input, counts). |

**`Column<T>`:** `{ key, header, render: (row) => ReactNode, className?, width? }`. `render` is a
full cell renderer (so columns can emit `StatusBadge`, `<code>`, formatted dates, action menus,
etc. — see `OrgsListPage` columns). `width` becomes an inline `style.width` on the `<TH>`.

**State & behavior:**
- **Pagination** — `useState(page=1)`; `totalPages = ceil(len/pageSize)` (min 1); the visible `slice`
  is `useMemo`'d (`data.slice((page-1)*pageSize, page*pageSize)`). The Prev/Next footer **only
  renders when `data.length > pageSize`** and shows "Page X of Y · N total" (tabular-nums); buttons
  disable at the ends. Pagination is purely client-side over the full `rows` array.
- **Three render branches** in `TBody`: `loading` → 5 skeleton `<tr>`s (each cell a `.skeleton`
  shimmer block sized `h-4 w-[70%]`, matching real layout so there is no layout shift on load);
  else empty slice → a single full-width `<td colSpan>` `EmptyState`; else the data rows.
- **Row interactivity (a11y)** — when `onRowClick` is set, each `TR` becomes keyboard-operable:
  `role="button"`, `tabIndex={0}`, `aria-label="Open row"`, `cursor-pointer`, a visible
  `focus-visible:ring-2 ring-inset ring-indigo-500` focus style, and an `onKeyDown` that activates on
  **Enter or Space** (with `preventDefault` to stop Space scrolling). Without `onRowClick` rows stay
  inert (no role/tabindex). This is purely additive — the click handler is unchanged.

**Styling.** The whole table sits in a `glass-solid` rounded surface (`ring-1 ring-slate-900/5
shadow-glass`) — denser/more-opaque glass than a Card, because data legibility must not depend on the
busy backdrop. The optional toolbar is a bordered header band.

---

### `ErrorBoundary.tsx`

**`ErrorBoundary`** — a class component (the only one in the kit, because error boundaries require
the class lifecycle). The top-level guard against a render-time exception white-screening the SPA
(the code cites finding **P1-17**: a contract mismatch like `.map` on a non-array would otherwise
unmount the whole app with no recovery).

**Props:** `children`, and an optional `fallback?: (error, reset) => ReactNode` to override the
default UI.

**Behavior.** `static getDerivedStateFromError` stores the `error` in state;
`componentDidCatch` logs `error` + `info.componentStack` to the console (observable / hookable by an
error reporter); `reset = () => setState({ error: null })` clears the error to retry the subtree. On
error with no custom `fallback`, it renders a centered `glass-card` panel: a danger warning icon, a
"Something went wrong" heading, the `error.message` in a `<pre>` (when present), and two actions —
**Try again** (calls `reset`, re-mounts children) and **Reload app** (`window.location.assign('/')`).
The fallback buttons are hand-styled to the Aurora Glass language rather than reusing `Button`
(deliberately dependency-free, since the boundary must render even if something in the kit is
implicated). Wrapped near the app root so any page error is caught.

---

### `PermissionGate.tsx`

**`PermissionGate({ permission, permissions, any, fallback, children })`** — element-level
authorization gate; conditionally renders `children` based on the current user's permission set.

| Prop | Type | Purpose |
|---|---|---|
| `permission` | `string?` | Single required permission. |
| `permissions` | `string[]?` | A set of permissions (takes precedence over `permission`). |
| `any` | `boolean?` | When true, require **any** of `permissions` (`.some`); otherwise **all** (`.every`). |
| `fallback` | `ReactNode?` | Rendered when the check fails; default `null`. |
| `children` | `ReactNode` | Rendered when the check passes. |

**Behavior.** Uses `useAuth().hasPermission`. Builds `list = permissions ?? (permission ? [permission]
: [])`; **if the list is empty it renders `children`** (no gate). Otherwise `ok = any ? list.some(...)
: list.every(...)`; renders `children` or `fallback`. Used to hide action buttons the user can't
perform (e.g. `<PermissionGate permission="org.write"><Button>New organization</Button></PermissionGate>`).

> **Security note.** This is **UI affordance only** — hiding a button does not protect the endpoint.
> The backend re-checks every authority. The codebase explicitly aligns gate codes to the
> *authorities the endpoint enforces* (finding **P3**: e.g. gate on `org.write`, the code the create
> endpoint actually requires, not a non-enforced `org.create`).

---

### `ProtectedRoute.tsx`

**`ProtectedRoute({ children })`** — the route-level auth gate, wrapped around protected route
elements in the router.

**Behavior.** Reads `useAuth()` → `{ user, ready }` and `useLocation()`.
- While `!ready` (the cookie-based `/me` bootstrap hasn't resolved yet) it renders `<PageLoader/>`
  — **not** a redirect. This is the load-bearing detail: a post-SSO or page-reload session would be
  wrongly bounced to `/login` if it redirected before `/me` settled.
- Once `ready`, if there is no `user` it `<Navigate to="/login" replace state={{ from:
  loc.pathname }} />` (preserving the intended path so login can return the user there).
- Otherwise it renders `children`.

Pairs with `AppShell`: typically `ProtectedRoute` wraps `AppShell`, so all in-shell pages require an
authenticated session.

---

## `src/lib/toast.tsx` — global notification layer

A context-based toast system: `ToastProvider` (mounted once near the app root) renders a fixed
notification stack and exposes `useToast()` to any descendant. Functionally part of the app chrome
even though it lives under `lib/`.

**Exports.**
- **`ToastProvider({ children })`** — provider + the rendered toast container.
- **`useToast(): ToastCtx`** — hook returning `{ push, success, error, info }`. **Throws** if used
  outside a provider (fail-fast contract).

**API (`ToastCtx`).** `push(message, kind?)` (kind defaults `'info'`) plus the three shorthands
`success(msg)`, `error(msg)`, `info(msg)`. Pages call `toast.success(...)` / `toast.error(apiErrorMessage(e))`
from react-query `onSuccess`/`onError`.

**State & lifecycle.**
- `toasts: Toast[]` in state (`{ id, kind, message }`); ids from a module-level `nextId` counter.
- `TOAST_TTL_MS = 4500` auto-dismiss. Timers are tracked in a `useRef<Map>` holding, per toast,
  `{ handle, remaining, startedAt }` — the wall-clock bookkeeping enables **pause/resume**.
- `scheduleDismiss(id, ms)` sets a timeout; `dismiss(id)` clears the timer and removes the toast;
  `pauseDismiss(id)` clears the timeout and stores the remaining time (`remaining - elapsed`);
  `resumeDismiss(id)` reschedules with the leftover time. The container wires
  `onMouseEnter/onFocus → pause` and `onMouseLeave/onBlur → resume`, so hovering or keyboard-focusing
  a toast **freezes its countdown** so it can be (re)read — this implements WCAG **2.2.1 Timing
  Adjustable**.
- A cleanup `useEffect` clears all pending timers on provider unmount.
- The `value` is `useMemo`'d so consumers don't re-render on unrelated provider state changes.

**Accessibility (deliberate live-region design).**
- The outer container is a neutral `role="region" aria-label="Notifications"` with **no
  `aria-live`** — intentionally, so an assertive region is never nested inside a polite one.
- Each toast carries its own live semantics: `role="alert"` for `error` (assertive), `role="status"`
  for success/info (polite).
- Each toast is `tabIndex={0}` (focusable so it can be paused/read), has a per-kind icon, and an
  `sr-only` label prefix ("Success: " / "Error: " / "Info: ") so meaning is announced and never
  rests on color alone.

**Motion/styling.** Stack is `pointer-events-none fixed` top-right (full-width inset on mobile,
`sm:max-w-sm` on the right on desktop); individual toasts re-enable pointer events. Entrance/exit via
`<AnimatePresence>` + the `toastSlide` variant from `@/lib/motion` (slide from the right + scale
settle), with `layout` so stacked toasts reflow smoothly. Each toast is a `glass-pop` surface
(crisp, high-opacity floating glass) with a per-kind border/text color from `KIND_STYLES`
(`success`/`danger`/`info` palettes), `shadow-glass-lg`, and a `focus-visible` ring.

---

## Supporting motion modules (referenced by the kit)

These are the canonical animation definitions the components import; documented here for context
(they are shared infrastructure, not feature code).

### `src/styles/motion.ts` — canonical motion tokens & variants

Single source of truth for animation. Exports:

- **`DURATION`** (seconds): `instant .1`, `fast .15`, `base .22`, `moderate .32`, `slow .5` —
  mirrors the Tailwind `transitionDuration` tokens.
- **`EASE`** (cubic-bezier arrays): `out` (easeOutQuint, standard entrances), `emphasized`
  (easeOutExpo, dialogs/routes), `in` (accelerate/exits), `standard` (Material in-out for layout).
- **`SPRING`**: `snappy` (button/card hover-lift), `gentle` (layout/reorder — used by the `Tabs`
  active indicator), `bouncy` (success accents).
- **Variants**: `fadeRise`, `staggerContainer`, `pageTransition` (used by `AppShell`'s `<Outlet/>`),
  `hoverLift`, `overlayPanel`, `overlayBackdrop`.

The header comment states the governing principle: **animate only transform + opacity, never
layout**; reserve orchestrated motion for first paint and navigation; keep interactive feedback
≤150ms. Accessibility is wired centrally via `<MotionConfig reducedMotion="user">` (in `main.tsx`)
plus the `index.css` CSS-only net.

### `src/lib/motion.ts` — convenience re-export barrel

Re-exports the `@/styles/motion` tokens/variants under the ergonomic `@/lib/*` namespace (so feature
code imports motion alongside `@/lib/cn`, `@/lib/toast`, etc., without duplicating values). It adds
exactly one additive variant not appropriate for the lower-level module: **`toastSlide`** (the
notification slide-in/out used by `toast.tsx`) — slide from the trailing edge + scale settle in,
accelerate out, transform/opacity only.

---

## Cross-cutting conventions & gotchas (quick reference)

- **Primitives wrap native elements, forward refs, and spread `...rest`** so they stay drop-in
  replacements (a page can pass any native attribute, event handler, or extra `className`). `cn`
  always appends the caller's `className` last.
- **`cn` is `clsx`, not `tailwind-merge`** — conflicting utilities both emit; extend, don't fight,
  base classes.
- **`invalid` is the shared error contract** across Input/Textarea/Select: it swaps to the rose
  treatment **and** sets `aria-invalid` (omitted when false). `Field` adds the
  `aria-describedby`/`aria-errormessage` *association*; the control owns the visual+`aria-invalid`
  *state*. Match `Field htmlFor` to the control `id`.
- **Status→color lives only in `StatusBadge`** — never re-map statuses ad hoc in a page.
- **Color is never the only signal** — Badge has a ring+label, toasts have an icon + `sr-only`
  prefix.
- **Two authorization layers, both affordance-only:** `ProtectedRoute` (route, waits for `/me`
  before deciding) and `PermissionGate` (element). The backend re-enforces; UI gate codes are
  aligned to the *enforced* authority (finding P3).
- **Modals trap focus & restore it** — `Dialog` (and the mobile nav drawer) capture/restore the
  trigger's focus, trap Tab, and close on Escape/backdrop.
- **Tabs/DataTable are keyboard-first** — roving-tabindex + arrow keys (Tabs); Enter/Space row
  activation with a visible focus ring (DataTable). Tabs emits `aria-controls` but the **page must
  render the matching `role="tabpanel"` container**.
- **Motion is centralized and reduced-motion-safe** — import from `@/styles/motion` /`@/lib/motion`;
  CSS animations are `motion-safe:`-gated; framer-motion obeys the global `MotionConfig`.
- **Glass surface tiers** are chosen by content: `glass-card`/`Card` (translucent panels),
  `glass-solid`/`DataTable` (opaque data surfaces), `glass-nav`/`glass-sticky` (chrome),
  `glass-pop`/toasts (crisp floating). `index.css` falls back to solid surfaces when
  `backdrop-filter` is unsupported or reduced-transparency is requested.
