# Aurora Glass — Design System, App Bootstrap & Tooling

## Module overview

This document covers the **foundation layer** of the `admin-ui` SPA — everything that exists
*before* a single feature page renders: the design-token source of truth, the global stylesheet that
paints the page and enforces accessibility, the React entry point that wires providers and fonts, the
shared motion vocabulary, and the build/TypeScript/container tooling that compiles and ships the app.

The visual language is **Aurora Glass**: a luminous, premium, **light-mode-only** look built from
frosted-glass panels floating over a soft, slowly-drifting aurora gradient-mesh backdrop, with
layered Stripe/Linear-style soft shadows for depth, vivid indigo → violet → cyan accents, Inter (UI)
and Sora (display) typography, and restrained transform/opacity micro-animations.

A central design choice runs through this whole layer, mirroring the backend's "live state beats
claims" discipline: **tokens are the single source of truth, and accessibility is non-negotiable.**
Colors, elevation, blur, radii, fonts, easing and keyframes live exactly once in
`tailwind.config.ts`; the glass surface recipes, the aurora backdrop, focus rings and the three
accessibility gates live exactly once in `src/index.css`; motion durations/easings/variants live
exactly once in `src/styles/motion.ts`. Feature components never re-derive these — they compose them
via Tailwind utilities and `.glass-*` helper classes. Every surface is engineered so that **dark text
on glass clears WCAG AA against the busiest, most-saturated region of the backdrop**, and three
independent media/feature queries (`@supports not (backdrop-filter)`,
`prefers-reduced-transparency`, `prefers-reduced-motion`) degrade the glamour to safe, solid,
still surfaces.

### How it fits the bigger picture

The control panel issues offline-verifiable Ed25519 `.lic` licenses to customer Docker apps; that
licensing path is entirely backend. The `admin-ui` SPA is the **human-facing console** for the
control panel's own admin surface — managing orgs, users, subscriptions, plans, licenses, keys, MFA,
SSO, billing, SCIM and audit. This design-system layer is what every one of those feature pages is
painted on. It collaborates with:

- **Tailwind + PostCSS** — `tailwind.config.ts` defines the token vocabulary; `postcss.config.js`
  runs Tailwind then Autoprefixer over `src/index.css`; the JIT engine scans `content` globs to
  tree-shake unused utilities.
- **`src/main.tsx`** — the composition root. Installs the provider stack (`ErrorBoundary` →
  `BrowserRouter` → `QueryClientProvider` → `ToastProvider` → `AuthProvider` → `MotionConfig`),
  imports the two self-hosted variable fonts, and imports `index.css` so the cascade is present
  before first paint.
- **`src/styles/motion.ts` / `src/lib/motion.ts`** — the framer-motion token/variant module and its
  ergonomic re-export barrel; consumed by every animated component.
- **`lib/*` contract modules** (`api.ts`, `types.ts`, `auth.tsx`, `queryClient.ts`) — explicitly
  *off-limits* to presentation work; the design system styles around them, never through them.
- **Vite + `vite.config.ts`** — dev server (port 5173, matching the backend CORS allowlist default),
  the `@/` path alias, the React plugin, and the Vitest config.
- **`Dockerfile` + `nginx.conf`** — the two-stage build that compiles the SPA to static assets and
  serves them from nginx with a per-deployment-configurable CSP, repeated security headers, and
  SPA-fallback routing.

---

## The big picture: how a pixel gets to the screen

```
  index.html  ──► <div id="root">  +  <script type="module" src="/src/main.tsx">
       │
       ▼
  main.tsx
   ├─ import '@fontsource-variable/inter'  ─► self-hosted Inter Variable @font-face + .woff2
   ├─ import '@fontsource-variable/sora'   ─► self-hosted Sora Variable  @font-face + .woff2
   ├─ import './index.css'                 ─► @tailwind base/components/utilities
   │      │                                    + :root tokens, body::before/::after aurora mesh,
   │      │                                    focus rings, .glass-* recipes, a11y gates
   │      ▼
   │   PostCSS (postcss.config.js): tailwindcss → autoprefixer
   │      ▲ scans tailwind.config.ts `content` globs, emits only used utilities
   │
   └─ ReactDOM.createRoot(#root).render(
          <StrictMode><ErrorBoundary><BrowserRouter>
            <QueryClientProvider><ToastProvider><AuthProvider>
              <MotionConfig reducedMotion="user">
                <App/>  ─► <AppRoutes/>  ─► feature pages compose .glass-* + motion variants
       )
```

At runtime the page is: a near-white `#f6f7fb` canvas → two fixed, GPU-promoted pseudo-element layers
painting the aurora mesh behind everything (`z-index:-1`) → glass cards/nav/popovers stacked on top,
each a translucent white surface with `backdrop-filter: blur() saturate()` sampling the mesh through
it → dark `ink` text that stays AA-legible because the glass floor opacity and the low-chroma mesh are
tuned together.

---

## File-by-file reference

### `tailwind.config.ts`

**The token source of truth.** A single typed `Config` object (`import type { Config } from
'tailwindcss'`) that *extends* (never replaces) Tailwind's defaults via `theme.extend`. The header
comment states the contract bluntly: **light-mode only, no `dark:` variants, no theme toggle**; this
file is the one place colors, elevation, blur, radii, fonts, easing and keyframes are defined, and
feature components apply them through utilities + the `.glass-*` helpers in `index.css`.

| Top-level key | Value | Purpose |
|---|---|---|
| `content` | `['./index.html', './src/**/*.{ts,tsx}']` | JIT scan set — every class string the purge keeps must appear literally in these files (no fully-dynamic class names). |
| `plugins` | `[]` | No Tailwind plugins; the glass helpers are hand-written in `index.css` `@layer components` instead. |

#### Colors (`theme.extend.colors`)

The palette is the heart of Aurora Glass. Every ramp is a full Tailwind-style scale unless noted.

- **`brand`** — `50…900` indigo ramp (`#eef2ff … #312e81`). This is a **preserved alias**: it used to
  be the old blue, and has been *re-pointed at the indigo accent* so any legacy `brand-600` reference
  silently reads as premium indigo (`#4f46e5`) instead of breaking. New code should prefer `indigo-*`,
  but `brand-*` keeps working — a deliberate backward-compat move.
- **`aurora`** — the signature decorative accent hues, **not** a numeric ramp:

  | Key | Hex | Role |
  |---|---|---|
  | `aurora.indigo` | `#6366f1` | primary mesh blob / indigo accent |
  | `aurora.violet` | `#a855f7` | mesh blob |
  | `aurora.fuchsia` | `#d946ef` | soft secondary blob layer |
  | `aurora.cyan` | `#22d3ee` | cyan accent / mesh |
  | `aurora.sky` | `#38bdf8` | mesh blob |

  These are **low-alpha / decorative only** — they feed the gradient-mesh blobs (kept in sync with the
  `--aurora-*` CSS variables in `index.css`), never meaningful text or AA-critical fills.
- **`indigo`** `50…900` — the primary-action ramp (vivid; AA-on-white at `600+`). `indigo-600
  #4f46e5` is the canonical primary; `indigo-500 #6366f1` is the gradient/chart start.
- **`violet`** `50…900` — companion accent. `violet-600 #7c3aed` is the primary gradient end.
- **`cyan`** `50…700` — companion accent (`cyan-400 #22d3ee`). Note: stops only to `700` (no `800/900`).
- **`ink`** — the **text scale**, defined with a `DEFAULT` so `text-ink` works, plus named steps. The
  comment encodes the contrast contract — these are the only colors copy may use, each verified AA on
  the glass floor:

  | Token | Hex (slate) | Use | Contrast note |
  |---|---|---|---|
  | `ink` (`DEFAULT`) | `#0f172a` (900) | headings / primary body | strongest |
  | `ink-soft` | `#334155` (700) | body | |
  | `ink-muted` | `#475569` (600) | secondary text | AA on ≥0.55 white glass |
  | `ink-faint` | `#64748b` (500) | large/muted text, placeholders, icons | large/decorative only |
  | `ink-ghost` | `#94a3b8` (400) | **decorative only — never meaningful text** | fails AA for body |

- **`surface`** — the light canvas + solid fills: `surface.canvas #f6f7fb` (page base behind the
  mesh; identical to the `body` background and the `theme-color` meta), `surface.base #ffffff`,
  `surface.subtle #f1f5f9` (slate-100; solid fills, divider backdrops), `surface.line #e2e8f0`
  (slate-200; solid hairlines).
- **Semantic ramps** — light-toned status colors, each `50/100/200/500/600/700`, intended to be
  **paired with an icon/label, never color-alone** (a colorblind-safe rule the components honor):
  `success` (emerald `#10b981`…), `warn` (amber `#f59e0b`…), `danger` (rose `#f43f5e`…), `info`
  (blue `#3b82f6`…).

#### Typography (`fontFamily`, `fontSize`)

- `fontFamily.sans` → `['"Inter Variable"', 'Inter', ...defaultTheme.fontFamily.sans]` — UI/body. The
  `"Inter Variable"` quoted name **must** match the family the `@fontsource-variable/inter` package
  registers, or it silently falls back to system sans.
- `fontFamily.display` → `['"Sora Variable"', 'Sora', ...defaultTheme.fontFamily.sans]` — h1/h2, page
  titles, big KPI numbers.
- `fontFamily.mono` → `['"JetBrains Mono"', ...defaultTheme.fontFamily.mono]` — keys/IDs/fingerprints
  (preserved; meaning unchanged). JetBrains Mono is *not* bundled, so this resolves to the system mono
  fallback chain unless the host has it — acceptable for the monospace technical strings it styles.
- `fontSize` adds a **tabular metric scale** for KPI values, each a `[size, { lineHeight, fontWeight }]`
  tuple with line-heights tuned for numerals:

  | Token | Size | Line-height | Weight |
  |---|---|---|---|
  | `text-metric-sm` | `1.5rem` | `1.75rem` | 600 |
  | `text-metric` | `1.875rem` | `2.25rem` | 600 |
  | `text-metric-lg` | `2.25rem` | `2.5rem` | 700 |

  Pair these with `tabular-nums` so KPI digits don't jitter on refetch.

#### Radii (`borderRadius`)

Extends the scale: `xl 0.875rem`, `2xl 1rem`, `3xl 1.5rem` (Tailwind's defaults remain for
`sm/md/lg`). The convention baked into the comment and `DESIGN_SYSTEM.md`: inputs/buttons use
`md`(0.5)/`lg`(0.75), **cards/panels use `2xl`(1rem)**, pills/avatars use `full`. Dense tables stay
at `xl` outer so the corner radius doesn't fight the row grid.

#### Blur (`blur`, `backdropBlur`)

The backdrop-filter blur scale; the documented sweet spot for readable glass is **12–18px**.

| Token | Value | Use |
|---|---|---|
| `blur-glass` / `backdrop-blur-glass` | `14px` | cards/panels |
| `blur-nav` / `backdrop-blur-nav` | `18px` | app chrome (sidebar/topbar) |
| `blur-blob` | `60px` | the soft secondary aurora blob layer (filter blur, not backdrop) |

Both a regular `blur` *and* a `backdropBlur` entry exist for `glass`/`nav` so the value is usable as
either a `filter` or a `backdrop-filter` utility.

#### Elevation (`boxShadow`)

Layered, low-opacity, **Stripe/Linear-style** soft depth — never a single dark drop shadow. Each token
is a *stack* of shadows so light reads as accumulating from multiple distances:

| Token | Recipe (abbrev.) | Use |
|---|---|---|
| `shadow-glass-sm` | 2 hairline shadows @ ~4–5% | inputs, small chips |
| `shadow-glass` | 3-layer 1/4/12px @ 4–6% | **default card resting** |
| `shadow-glass-lg` | 3-layer up to 24/48px, −12 spread @ up to 12% | card hover / raised |
| `shadow-glass-xl` | 2-layer 16/64px, negative spread @ up to 18% | dialogs / popovers |
| `shadow-glass-inset` | `inset 0 1px 0 rgba(255,255,255,0.7)` | the 1px **rim-of-light** on glass |
| `shadow-glow` | `0 4px 14px -4px rgba(99,102,241,0.5)` | indigo glow under the primary gradient button |
| `shadow-glow-lg` | `0 6px 20px -4px rgba(99,102,241,0.6)` | stronger glow on hover |
| `shadow-focus` | `0 0 0 2px #fff, 0 0 0 4px #6366f1` | focus-ring fallback (prefer `focus-visible:ring-*` utilities) |

Performance rule (from `DESIGN_SYSTEM.md`): a hover-lift transitions `transform` and **swaps to a
precomputed larger shadow** (`shadow-glass` → `shadow-glass-lg`); it never animates `box-shadow` blur
or `backdrop-filter`, both of which are per-frame paint.

#### Gradients (`backgroundImage`)

| Token | Value | Use |
|---|---|---|
| `bg-aurora-primary` | `linear-gradient(135deg,#4f46e5 0%,#7c3aed 100%)` | primary action button |
| `bg-aurora-primary-hover` | `linear-gradient(135deg,#4f46e5 0%,#6d28d9 100%)` | its hover (darker end) |
| `bg-aurora-chip` | `linear-gradient(135deg, rgba(99,102,241,.15), rgba(34,211,238,.15))` | soft indigo→cyan icon-chip fill (empty-state circles) |
| `bg-aurora-text` | `linear-gradient(135deg,#4f46e5,#7c3aed 50%,#0891b2)` | gradient display text (sparing; keep AA) |

> **Accessibility gotcha baked into the gradient.** The primary gradient deliberately *starts at
> indigo-600 `#4f46e5`*, not the lighter indigo-500 `#6366f1`. The comment explains why: white text on
> `#4f46e5` clears WCAG AA at 5.6:1 along the lightest stop, whereas `#6366f1` was only 4.47:1 — which
> would fail for small/medium button labels. The token encodes the contrast fix so no component can
> accidentally reintroduce the low-contrast start.

#### Motion tokens (`transitionTimingFunction`, `transitionDuration`, `keyframes`, `animation`)

These are the **CSS half** of the motion system (the framer half is `src/styles/motion.ts`, with
which they are kept numerically identical).

Easing (`ease-*`):

| Token | cubic-bezier | Use |
|---|---|---|
| `ease-out-quint` | `0.22, 1, 0.36, 1` | standard entrances (snappy then soft) |
| `ease-out-expo` | `0.16, 1, 0.3, 1` | emphasized — dialogs / route |
| `ease-in-quint` | `0.64, 0, 0.78, 0` | exits / accelerate |
| `ease-standard` | `0.4, 0, 0.2, 1` | Material standard in-out (layout shifts) |

Durations (`duration-*`, ms): `instant 100` · `fast 150` · `base 220` · `moderate 320` · `slow 500`.

Keyframes + their `animation` shorthands:

| Animation | Keyframe behavior | Default timing | Notes |
|---|---|---|---|
| `animate-fade-up` | opacity 0→1 + `translateY(12px)`→0 | `0.32s ease-out-quint both` | CSS entrance |
| `animate-fade-in` | opacity 0→1 | `0.22s ease-out-quint both` | |
| `animate-scale-in` | opacity + `translateY(8px) scale(.96)`→1 | `0.28s ease-out-expo both` | dialog/popover |
| `animate-shimmer` | `backgroundPosition -200%→200%` | `1.6s linear infinite` | drives `.skeleton` |
| `animate-aurora-drift` | `translate3d`+`scale` drift | `28s ease-in-out infinite alternate` | ambient mesh |
| `animate-float` | `translateY(0→-4px→0)` | `6s ease-in-out infinite` | decorative chips |
| `animate-pulse-ring` | `translate(-50%,-50%) scale(1→3)` + opacity 0.5→0 | `2s cubic-bezier(.4,0,.6,1) infinite` | live/status dot ring |

> **Two performance notes encoded in the keyframes.** (1) `aurora-drift` animates only
> `transform`/`scale` on a compositor-promoted layer — never the gradient itself. (2) `pulse-ring`
> drives a **separate ring pseudo-element** that scales+fades (transform/opacity) rather than animating
> `box-shadow` spread, which would repaint every frame. The matching `.pulse-ring` recipe lives in
> `index.css`.

---

### `src/index.css`

The **global stylesheet** — the runtime expression of the tokens. It is the only file that (a) pulls
in Tailwind's three layers, (b) declares the CSS custom properties, (c) paints the aurora backdrop,
(d) defines the `.glass-*` component recipes, and (e) ships the three accessibility gates. Loaded once
from `main.tsx`.

**Layer imports.** `@tailwind base; @tailwind components; @tailwind utilities;` — the standard
Tailwind cascade; the custom recipes are injected into the `components` layer via `@layer components`
so utilities can still override them.

**`:root` custom properties.** Two groups:

- The **aurora mesh hues** as comma-separated RGB triples (`--aurora-indigo: 99, 102, 241;` …), kept
  in sync with the Tailwind `aurora` tokens. They're stored as raw channels so the backdrop can wrap
  them in `rgba(var(--aurora-indigo), 0.26)` at varying alpha.
- The **glass surface opacities** — the *contrast contract* as variables: `--glass-card: 0.7`
  (text/data panels), `--glass-chrome: 0.6` (nav/topbar), `--glass-sticky: 0.8` (sticky headers, since
  content scrolls under them). Centralizing these means the AA floor can be retuned in one place.

**`html, body, #root { height: 100% }`** — full-height shell so the SPA and the fixed backdrop fill the
viewport.

**`body` base.** Background `#f6f7fb` (the canvas), color `#0f172a` (ink — dark, high-contrast
default), the full Inter font stack with system fallbacks, `font-feature-settings: 'cv11','ss01'`
(Inter stylistic sets for a cleaner single-storey `a`/`g`), plus antialiasing
(`-webkit-font-smoothing: antialiased`, `-moz-osx-font-smoothing: grayscale`) and
`text-rendering: optimizeLegibility`.

**The aurora backdrop (`body::before` + `body::after`).** This is the signature surface:

- `body::before` — the **radial gradient mesh**. `position: fixed; inset: -10%` (overscan so drift
  never reveals a hard edge), `z-index: -1`, `pointer-events: none`, `transform: translateZ(0)` to
  promote it to its own compositor layer. The `background-image` stacks four radial gradients (indigo
  @12%/8% α0.26, violet @88%/12% α0.20, cyan @75%/85% α0.18, sky @15%/92% α0.16), each faded to
  `transparent` at ~60%. The low alphas are deliberate — high enough to read as luminous, low enough
  that dark text on the glass above stays AA.
- `body::after` — a **second, softly-blurred blob layer** for depth *without* a second
  `backdrop-filter`. Same fixed/inset/z-index/`pointer-events`, but with `filter: blur(60px)` and
  `opacity: 0.55`, painting two more blobs (fuchsia, indigo). Using a `filter: blur` here (on a
  decorative layer) rather than stacking another backdrop-filter keeps the expensive blur count down.

Both drift only under `prefers-reduced-motion: no-preference` (see gates) — `::before` runs
`aurora-drift 28s … alternate`, `::after` runs `36s … alternate-reverse` for parallax.

**Typography defaults.** `h1, h2 { font-family: 'Sora Variable' … ; letter-spacing: -0.02em }` — the
display face + tight tracking applied globally so headings get Sora without per-component classes.

**Focus-visible rings.** A single `:where(a, button, [role='button'], input, select, textarea,
[tabindex]):focus-visible` rule clears the native `outline` and applies a **two-tone ring**:
`0 0 0 2px #ffffff, 0 0 0 4px rgba(99,102,241,0.6)` (a white separator ring under an indigo ring) plus
`border-radius: 0.5rem`. Using `:where()` keeps specificity at 0 so component primitives can *upgrade*
to `focus-visible:ring-2 ring-indigo-500 ring-offset-2` without a specificity war — but the floor is
always a visible ring. This is the a11y backbone for keyboard users.

**Custom scrollbars.** Slim, light, unobtrusive: Firefox `scrollbar-width: thin` +
`scrollbar-color`; WebKit 10px track (transparent) with a translucent slate thumb
(`rgba(100,116,139,0.3)`, `border-radius: 9999px`, content-box border for inset padding) that darkens
on hover. Purely cosmetic; doesn't affect layout.

**Glass component recipes (`@layer components`).** The reusable surface vocabulary — each carries the
`-webkit-backdrop-filter` prefix *and* participates in all three a11y gates below:

| Class | Background | Backdrop-filter | Border / shadow | Use |
|---|---|---|---|---|
| `.glass` | `rgba(255,255,255, var(--glass-card))` = 0.70 | `blur(14px) saturate(170%)` | white/60 border + 3-layer glass shadow + inset rim | base frosted card/panel with text |
| `.glass-card` | 0.70 | `blur(14px) saturate(170%)` | same as `.glass` **+ `border-radius: 1rem`** | the canonical card preset |
| `.glass-solid` | `rgba(255,255,255,0.85)` | `blur(12px) saturate(150%)` | white/60 border | dense data tables / forms where legibility can't depend on the busy backdrop |
| `.glass-nav` | 0.60 (`--glass-chrome`) | `blur(18px) saturate(160%)` | (none) | sidebar / topbar chrome — heavier blur, thinner surface |
| `.glass-sticky` | 0.80 (`--glass-sticky`) | `blur(16px) saturate(160%)` | (none) | sticky table heads / topbar so content scrolling under stays legible |
| `.glass-pop` | `rgba(255,255,255,0.9)` | `blur(12px) saturate(160%)` | white/70 border | tooltips / dropdowns / floating — small, crisp, high opacity |

The `saturate(150–170%)` is what makes the glass feel *luminous* rather than merely frosted — it
re-saturates the muted mesh sampled through it. Opacity climbs as the surface gets more
legibility-critical (chrome 0.60 → cards 0.70 → sticky 0.80 → tables 0.85 → popovers 0.90), the
**glass opacity ladder**.

**`.pulse-ring`** — the live/status-dot ring. `position: relative` host; the `::after` pseudo-element
is an absolutely-centered full-size emerald circle (`rgba(16,185,129,0.45)`) that runs the `pulse-ring`
keyframe (scale 1→3, fade out). It animates transform/opacity only (never `box-shadow` spread) so it's
compositor-only. A scoped `@media (prefers-reduced-motion: reduce)` inside the recipe kills the
animation and hides the ring (`opacity: 0`) — a belt-and-suspenders alongside the global gate.

**`.skeleton`** — loading shimmer block: a 3-stop slate gradient at `background-size: 200% 100%`
running `shimmer 1.6s linear infinite`, `border-radius: 0.5rem`. Sized by the consumer to match the
real layout it's standing in for.

**Accessibility gates — "all three ship together."** This is the safety net that makes the glamour
safe, and the comment insists they're a set:

1. **No `backdrop-filter` support** — `@supports not ((backdrop-filter: blur(1px)) or
   (-webkit-backdrop-filter: blur(1px)))` → the text-bearing surfaces
   (`.glass/.glass-card/.glass-solid/.glass-pop`) become near-opaque `rgba(255,255,255,0.94)` and the
   chrome surfaces (`.glass-nav/.glass-sticky`) `0.96`. The glass *never* renders transparent on a
   browser that can't blur the backdrop behind it (which would put dark text on the raw mesh).
2. **Reduced transparency** — `@media (prefers-reduced-transparency: reduce)` → *all* glass surfaces go
   fully solid `#ffffff` with `backdrop-filter: none`, and the two backdrop layers drop to
   `opacity: 0.5`. Honors the OS "Reduce transparency" preference (common for low-vision users).
3. **Reduced motion** — `@media (prefers-reduced-motion: reduce)` → a global `*, *::before, *::after`
   rule clamps `animation-duration`/`transition-duration` to `0.001ms`, forces
   `animation-iteration-count: 1` and `scroll-behavior: auto`, all `!important`; plus an explicit
   `body::before/::after { animation: none !important }` to **freeze the aurora drift**. This is the
   CSS-only counterpart to framer's `MotionConfig reducedMotion="user"` — defense in depth, so even
   non-framer CSS animation respects the preference.

The final block — `@media (prefers-reduced-motion: no-preference)` — is the *positive* gate that
actually *starts* the aurora drift (28s on `::before`, 36s reverse on `::after`), so motion is opt-in
by absence-of-objection rather than always-on-then-disabled.

---

### `index.html`

The Vite HTML entry / shell. Minimal by design — the SPA mounts into it.

- `<html lang="en">` — language for screen readers.
- `<link rel="icon" type="image/svg+xml" href="/favicon.svg">` — the SVG favicon (a rounded blue
  `#2563eb` square with white "CP" — Control Panel; served from `public/favicon.svg`).
- `<meta name="viewport" content="width=device-width, initial-scale=1.0">` — responsive baseline.
- `<meta name="color-scheme" content="light">` and `<meta name="theme-color" content="#f6f7fb">` —
  pin the UA to **light** (no dark form controls / scrollbars) and color the mobile browser chrome to
  match the canvas. These reinforce the "light mode only" hard rule at the document level.
- `<meta name="description" content="Control Panel — license & organization management.">` and
  `<title>Control Panel</title>`.
- `<body>` holds `<div id="root"></div>` (the React mount point referenced by `main.tsx`) and
  `<script type="module" src="/src/main.tsx">` (Vite injects the bundled module here).

There is no inline CSS or font `<link>` — fonts are self-hosted via npm imports in `main.tsx`, and all
styling arrives through the bundled `index.css`. Keeping the shell empty avoids a flash of unstyled
content tied to external font CDNs and keeps the CSP tight (no third-party origins needed).

---

### `src/main.tsx`

The **composition root** — the single `ReactDOM.createRoot(...).render(...)` call that boots the app
and establishes the provider stack and global side-effect imports.

**Side-effect imports (order matters):**
- `@fontsource-variable/inter` and `@fontsource-variable/sora` — self-hosted variable fonts. The
  comment flags them as **presentation-only, no contract impact**. Importing here (not in `lib/*`)
  registers the `@font-face` rules + bundles the `.woff2` so the families named in `tailwind.config.ts`
  (`"Inter Variable"`, `"Sora Variable"`) actually resolve. Self-hosting (vs. Google Fonts) keeps the
  nginx CSP `font-src`/`script-src` to `'self'` and avoids an external request on first paint.
- `./index.css` — pulls the entire Aurora Glass cascade into the bundle.

**Provider stack** (outermost → innermost), each layer wrapping the next:

| Wrapper | Source | Responsibility |
|---|---|---|
| `React.StrictMode` | react | dev-time double-invoke / deprecation checks |
| `ErrorBoundary` | `./components/ErrorBoundary` | catches render errors, shows a fallback instead of a white screen |
| `BrowserRouter` | react-router-dom | HTML5 history routing (pairs with nginx SPA fallback) |
| `QueryClientProvider` | @tanstack/react-query | supplies the shared `queryClient` (server-state cache; the design system styles its loading/empty/error states) |
| `ToastProvider` | `./lib/toast` | app-wide toast surface (rendered with the `toastSlide` motion variant) |
| `AuthProvider` | `./lib/auth` | session/identity context (a contract module — off-limits to styling) |
| `MotionConfig reducedMotion="user"` | framer-motion | **global motion accessibility** |
| `<App/>` → `<AppRoutes/>` | `./App`, `./routes` | the route tree / feature pages |

> **The one design-system-relevant provider: `MotionConfig reducedMotion="user"`.** The inline comment
> says it plainly — framer respects the OS *Reduced Motion* setting globally, stripping
> transform/layout animation for users who request it. Combined with the CSS-only reduced-motion gate
> in `index.css`, the app has **two independent reduced-motion safety nets** (framer-managed +
> CSS-managed), so no animated component can slip through regardless of whether it animates via framer
> or raw CSS.

`App.tsx` itself is a one-liner (`export function App() { return <AppRoutes/>; }`) — a thin seam
between the provider stack and the route tree, keeping `main.tsx` focused on wiring.

---

### `src/styles/motion.ts`

The **framer-motion source of truth** — durations, easings, springs and reusable variants. Imported
instead of re-deriving timing per component, so animation stays consistent and tunable in one place.
The header encodes the motion philosophy and its two accessibility ties (the `MotionConfig` in
`main.tsx` + the CSS net in `index.css`).

> **Motion principle (the load-bearing rule):** animate **only `transform` (translate/scale) and
> `opacity`** — never `width/height/top/left/margin` (layout thrash, made worse over the glass blur).
> Reserve orchestrated motion (route/stagger/presence) for first paint and navigation; keep
> interactive feedback fast (≤150ms) and minimal. Restraint reads as premium.

**`DURATION`** (seconds — framer's unit; mirror of the Tailwind ms tokens): `instant 0.1`, `fast 0.15`,
`base 0.22`, `moderate 0.32`, `slow 0.5`. `as const` so they're literal types.

**`EASE`** (cubic-bezier arrays — framer's `ease`): `out [0.22,1,0.36,1]` (entrances), `emphasized
[0.16,1,0.3,1]` (dialogs/route), `in [0.64,0,0.78,0]` (exits), `standard [0.4,0,0.2,1]` (layout).
Numerically identical to the Tailwind `ease-out-quint`/`ease-out-expo`/`ease-in-quint`/`ease-standard`
so CSS and framer motion feel the same.

**`SPRING`** (duration-less, interruptible — ideal for hover/press/layout):

| Preset | Config | Use |
|---|---|---|
| `snappy` | `{ stiffness:400, damping:30, mass:0.8 }` | button / card hover-lift |
| `gentle` | `{ stiffness:260, damping:26 }` | layout / reorder |
| `bouncy` | `{ stiffness:500, damping:18 }` | success accents only — sparingly |

(The framer default spring is deliberately avoided — it's floaty.)

**Variants / recipes** (typed `Variants` or `as const` objects):

| Export | Shape | Use |
|---|---|---|
| `fadeRise` | `hidden {opacity:0,y:12}` → `show {opacity:1,y:0}` @ base/out | default entrance for cards/sections/page content |
| `staggerContainer` | `show.transition { staggerChildren:0.05, delayChildren:0.04 }` | parent that staggers `fadeRise` children (KPI grids, card lists, nav) |
| `pageTransition` | `initial {opacity:0,y:8}` / `animate {…y:0}` / `exit {…y:-8}` @ base/emphasized | wrap `<Outlet/>`; small `y` keeps nav feeling instant |
| `hoverLift` | `whileHover {y:-2, scale:1.01}`, `whileTap {scale:0.98}`, `SPRING.snappy` | the dynamic signature — glass cards/buttons; restraint (≤4px lift, ≤1.02 scale) |
| `overlayPanel` | `hidden {opacity:0,scale:0.96,y:8}` / `show` (base/emphasized) / `exit` (fast/in) | Dialog/dropdown/toast panel presence (with `AnimatePresence`) |
| `overlayBackdrop` | opacity 0↔1, base in / fast out | the dimmer behind an overlay |

These are pure data (no DOM) — components spread them onto `motion.*` elements.

---

### `src/lib/motion.ts`

A thin **convenience barrel** living alongside the other `@/lib/*` utilities. It exists so feature code
can import shared animation primitives from one ergonomic `@/lib/motion` path *without duplicating* the
underlying values (which would risk divergence). The header is explicit that
`@/styles/motion` remains the canonical source.

- **Re-exports** every token/variant from `@/styles/motion`: `DURATION, EASE, SPRING, fadeRise,
  staggerContainer, pageTransition, hoverLift, overlayPanel, overlayBackdrop`.
- **Adds one additive, presentation-only variant** that didn't belong in the lower-level styles module:

  **`toastSlide`** (`Variants`): slides in from the trailing/right edge with a gentle scale settle and
  accelerates out — `hidden {opacity:0, x:24, scale:0.96}` → `show {…x:0, scale:1}` (base/emphasized)
  → `exit {…x:24, scale:0.98}` (fast/in). Built from the re-imported `DURATION`/`EASE` so it inherits
  the same timing vocabulary, and (like every variant) animates transform+opacity only and inherits the
  project reduced-motion handling. Used by the toast surface mounted in `ToastProvider`.

The split is intentional: `styles/motion.ts` holds *primitives*; `lib/motion.ts` is the *app-facing
import surface* (primitives + a couple of page-level extras), keeping `@/lib/*` the one place feature
code reaches for utilities.

---

### `src/lib/cn.ts`

The class-name merge helper used throughout the component layer: `cn(...inputs: ClassValue[]) =>
clsx(inputs)`. A one-line wrapper over `clsx` that conditionally joins class strings (so components can
write `cn('glass-card', isActive && 'ring-2', className)`). Note it is **`clsx`-only** — there is no
`tailwind-merge`, so callers are responsible for not passing genuinely conflicting Tailwind utilities;
last-wins class de-duplication is not performed. It centralizes the dependency so swapping the
implementation later touches one file.

---

### `src/App.tsx`

`export function App() { return <AppRoutes/>; }` — a deliberately trivial seam between the provider
stack (`main.tsx`) and the route tree (`routes.tsx`, a contract file). Nothing design-system-specific
lives here; it exists so `main.tsx` mounts a single named component.

---

### `src/vite-env.d.ts`

Ambient TypeScript for the Vite client. `/// <reference types="vite/client" />` pulls in
`import.meta.*` typings, then augments `ImportMetaEnv` with the one app env var,
`readonly VITE_API_BASE?: string` (the backend origin), and re-declares `ImportMeta.env`. Build-time
only — it shapes the type of `import.meta.env.VITE_API_BASE` consumed by the (contract) API layer.

---

### `public/favicon.svg`

The browser tab icon: an inline SVG, `viewBox 0 0 32 32`, a rounded (`rx=6`) `#2563eb` blue square
with centered bold white "CP" text. Referenced by `index.html`. (It predates the indigo accent
re-point and still uses the older blue — a cosmetic legacy detail, not part of the token system.)

---

## Build & TypeScript tooling

### `vite.config.ts`

The build/dev/test config. `/// <reference types="vitest/config" />` lets Vitest's `test` field be
typed in the same file.

- `plugins: [react()]` — `@vitejs/plugin-react` (Fast Refresh in dev, the React automatic JSX runtime
  in build; matches `"jsx": "react-jsx"` in tsconfig).
- `resolve.alias['@'] = path.resolve(__dirname, './src')` — the `@/` path alias, kept in lockstep with
  the `paths` mapping in `tsconfig.app.json` (Vite resolves it at bundle time; TS resolves it at
  type-check time — both must agree).
- `server: { host: true, port: 5173 }` — dev server on **5173**, the default the backend's
  `app.cors.allowed-origins` permits (`http://localhost:5173`), and `host: true` binds all interfaces
  (LAN/container access).
- `test: { environment: 'node', include: ['src/**/*.{test,spec}.{ts,tsx}'] }` — Vitest runs in a Node
  environment (the suites here are contract/unit tests, not DOM tests) over the test glob.

### `tsconfig.json` (solution root)

A **project-references** root: `"files": []` with `references` to `./tsconfig.app.json` and
`./tsconfig.node.json`. It compiles nothing itself; `tsc -b` walks the references. This split lets app
code and build-tooling code (Vite config) be type-checked with different libs/targets.

### `tsconfig.app.json` (application code)

The settings for `src/**`:

| Option | Value | Why |
|---|---|---|
| `target` / `lib` | `ES2020` / `ES2020, DOM, DOM.Iterable` | modern browser baseline + DOM typings |
| `module` / `moduleResolution` | `ESNext` / `bundler` | Vite handles bundling; bundler resolution allows extensionless + TS-extension imports |
| `useDefineForClassFields` | `true` | spec-correct class fields |
| `jsx` | `react-jsx` | automatic runtime (no `import React` needed for JSX) |
| `strict` | `true` | full strictness |
| `noUnusedLocals` / `noUnusedParameters` | `false` | relaxed (don't fail builds on scaffolding) |
| `noFallthroughCasesInSwitch` | `true` | switch-safety |
| `allowImportingTsExtensions` + `isolatedModules` + `noEmit` | — | type-check only; Vite emits |
| `resolveJsonModule`, `esModuleInterop`, `skipLibCheck` | — | JSON imports, interop, faster checks |
| `baseUrl: "."` + `paths { "@/*": ["src/*"] }` | — | the `@/` alias for the type checker |
| `include` / `exclude` | `["src"]` / test+spec files | app build excludes test files (they're run by Vitest, not built) |

### `tsconfig.node.json` (build tooling)

Scoped to `vite.config.ts` only. `target ES2022`, `lib ES2023`, `module ESNext`,
`moduleResolution bundler`, `strict`, `types: ["node"]`, `noEmit` — typechecks the config file against
Node typings (note `@types/node` is a devDependency). Separated from app code so Node globals/`__dirname`
don't leak into the browser bundle's type space.

### `postcss.config.js`

Two plugins in order: `tailwindcss` (runs the JIT engine against `tailwind.config.ts`, expanding
`@tailwind` directives + scanning `content`) then `autoprefixer` (adds vendor prefixes). Autoprefixer
is what would normally generate `-webkit-` prefixes, but the `.glass-*` recipes also hand-write
`-webkit-backdrop-filter` because Autoprefixer historically doesn't prefix `backdrop-filter`
reliably — belt-and-suspenders for Safari.

### `package.json`

`"name": "admin-ui"`, `"private": true`, `"type": "module"` (ESM). Scripts:

| Script | Command | Purpose |
|---|---|---|
| `dev` | `vite` | dev server (Fast Refresh) on 5173 |
| `build` | `tsc -b && vite build` | typecheck via project refs, then production bundle to `dist/` |
| `preview` | `vite preview --host` | serve the built `dist/` locally |
| `lint` | `tsc -b --noEmit` | typecheck-only (the project's "lint" is the TS strict check) |
| `test` | `vitest run` | one-shot Vitest run |

Dependencies relevant to the design system: **`framer-motion ^11`** (the animation engine — the only
animation lib, per the "no new deps" rule), **`@fontsource-variable/inter`** + **`@fontsource-variable/sora ^5`**
(the self-hosted variable fonts), **`clsx`** (the `cn` helper), **`recharts ^2`** (charts — styled but
not animated by the design system), plus the contract stack (`@tanstack/react-query`, `axios`,
`react-hook-form`, `@hookform/resolvers`, `zod`, `react-router-dom`, `react`/`react-dom`). Dev deps:
`tailwindcss ^3.4`, `postcss`, `autoprefixer`, `vite ^5`, `vitest ^2`, `typescript ^5.6`, the React
types, `@vitejs/plugin-react`, `@types/node`. The hard rule **"no new deps beyond `framer-motion` and
`@fontsource-variable/*`"** is enforced by this list — no other chart/animation library may be added.

### `.env.example`

A single var: `VITE_API_BASE=http://localhost:8080` — the backend origin the (contract) API layer
points at. Copied to `.env` for local dev; baked at build time (Vite inlines `VITE_*` vars). `.env`
itself is gitignored.

### `.gitignore`

Ignores `node_modules`, `dist`, `dist-ssr`, `.env`, `.env.local`, `*.log`, `.vite` — standard Vite
artifacts and secrets.

---

## Container & serving

### `Dockerfile`

A two-stage build:

1. **`build` stage** (`node:22-alpine`): `npm ci || npm install` (CI-reproducible install, falling
   back to a plain install if no lockfile), copy source, set `ARG/ENV VITE_API_BASE`
   (default `http://localhost:8080`) so the API origin is inlined at build time, then `npm run build`
   → static assets in `/app/dist`.
2. **serve stage** (`nginx:alpine`): copy `dist` to `/usr/share/nginx/html`, and copy `nginx.conf` to
   `/etc/nginx/templates/default.conf.template`. The official nginx entrypoint **envsubst-renders**
   `*.template` → `/etc/nginx/conf.d/` at container start, substituting `${CSP_CONNECT_SRC}`. That env
   defaults to the build-time `VITE_API_BASE` but is overridable at `docker run`/compose time, so the
   CSP's allowed API origin is per-deployment, not hardcoded. `EXPOSE 80`; `CMD ["nginx","-g","daemon
   off;"]`.

### `nginx.conf`

The static-SPA server (rendered through envsubst). Highlights:

- **HTTP→HTTPS** handling: if `$http_x_forwarded_proto = "http"` (TLS terminated upstream by an
  LB/ingress) → `301` to `https://`; this is gated on the forwarded proto so it doesn't loop. A
  commented in-container TLS terminator (`listen 443 ssl`, mounted certs) is provided for the
  self-contained compose setup.
- **SPA fallback**: `location / { try_files $uri $uri/ /index.html; }` — any unknown path serves
  `index.html` so client-side `BrowserRouter` routes resolve (deep links / refresh work).
- **Security headers**, applied in **every** `location` block: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Strict-Transport-Security` (1-year,
  includeSubDomains), and a **CSP**:
  `default-src 'self'; connect-src 'self' ${CSP_CONNECT_SRC}; img-src 'self' data:; style-src 'self'
  'unsafe-inline'; script-src 'self'`. `connect-src` adds the configurable API origin so the SPA can
  call the backend; `style-src 'unsafe-inline'` is required for the inlined critical/utility styles;
  everything else is locked to `'self'` (which is why the fonts are self-hosted — no third-party
  origins).
- **Caching**: `location /assets/` gets `expires 7d` + `Cache-Control: public, immutable` (Vite
  content-hashes asset filenames, so immutable caching is safe).

> **Gotcha encoded in the file (finding P3):** nginx's `add_header` does **not** inherit into a
> `location` that defines its own `add_header` directives. So the security headers are *repeated*
> verbatim in `location /assets/` — without that, the hashed JS/CSS/font assets would ship with **no**
> CSP / `X-Frame-Options` / `nosniff`. The comment calls this out explicitly so a future edit doesn't
> "DRY it up" and silently strip the asset headers.

---

## Cross-cutting invariants (quick reference)

- **One source per concern.** Tokens → `tailwind.config.ts`; surfaces + backdrop + a11y gates + focus
  rings → `index.css`; motion → `src/styles/motion.ts` (re-exported via `src/lib/motion.ts`). Never
  re-derive these in feature code.
- **Light mode only.** `color-scheme: light`, `<meta color-scheme/theme-color>`, no `dark:` variants,
  no toggle. Backgrounds light, text dark and high-contrast.
- **AA against the worst case.** Body ≥4.5:1, large/bold ≥3:1, verified against the *busiest* region
  of the mesh — which is why mesh alphas are low, the glass floor is `/70` for text panels, and the
  primary gradient starts at indigo-600 not indigo-500.
- **The glass opacity ladder:** chrome 0.60 → cards 0.70 → sticky 0.80 → tables 0.85 → popovers 0.90;
  never put meaningful text on a `<0.55` surface; cap overlapping backdrop-filter layers at ~2 (prefer
  `.glass-solid` for dense data).
- **Animate transform + opacity only**, never layout properties or `backdrop-filter`. Hover-lift swaps
  to a *precomputed* larger shadow. Keep interactive feedback ≤150ms; reserve orchestration for first
  paint / navigation.
- **Two reduced-motion nets** (framer `MotionConfig reducedMotion="user"` + the CSS `@media
  (prefers-reduced-motion)` clamp), plus the `@supports not (backdrop-filter)` and
  `prefers-reduced-transparency` gates — "all three ship together."
- **`-webkit-backdrop-filter` always alongside `backdrop-filter`** — hand-written in the recipes since
  Autoprefixer doesn't reliably prefix it (Safari support).
- **Self-hosted fonts** (`@fontsource-variable/*`, imported in `main.tsx`, family names *must* carry
  the `Variable` suffix or they silently fall back) keep the CSP to `'self'` — no font CDN.
- **No new deps** beyond `framer-motion` + `@fontsource-variable/*`; no other chart/animation library.
- **`@/` alias** must stay in sync between `vite.config.ts` (`resolve.alias`) and `tsconfig.app.json`
  (`paths`).
- **Dev port 5173** matches the backend CORS allowlist default; **nginx repeats security headers per
  `location`** (finding P3) and makes the CSP `connect-src` origin deployment-configurable via
  `${CSP_CONNECT_SRC}`.

---

## See also

- **`admin-ui/DESIGN_SYSTEM.md`** — the in-repo agent-facing brief this document is the reference
  companion to. It carries the **HARD RULES** (light-mode only; preserve all functionality/contracts,
  with the explicit off-limits list — `lib/api.ts`, `lib/types.ts`, `lib/auth.tsx`,
  `lib/queryClient.ts`, the `*.test`/`*-contract.test` files, `routes.tsx`; WCAG AA; no new deps;
  `@/` import style), plus the canonical copy-paste **card class**
  (`rounded-2xl border border-white/60 bg-white/70 backdrop-blur-glass backdrop-saturate-150
  shadow-glass ring-1 ring-slate-900/5`) and the **state-treatment recipes** (loading skeletons,
  empty-state glass card with `bg-aurora-chip` icon circle, rose-tinted glass error note
  `bg-danger-50/70 border border-danger-200 text-danger-700 rounded-xl`). When `DESIGN_SYSTEM.md` and
  this doc agree, they are describing the same tokens from the two files above; when in doubt, the
  *code* (`tailwind.config.ts` / `index.css` / `styles/motion.ts`) is authoritative.
- **`docs/backend/auth.md`** — the house-style reference for these module docs.
