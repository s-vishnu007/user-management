# Aurora Glass — Design System

A luminous, premium, **light-mode** design language for the Control Panel admin SPA: frosted-glass panels over a soft aurora gradient-mesh backdrop, layered soft shadows for depth, vivid indigo → violet → cyan accents, and fluid, tasteful CSS/framer micro-animations.

> Single source of truth. Tokens live in `tailwind.config.ts`; surfaces, the aurora backdrop, focus rings and accessibility gates live in `src/index.css`; motion variants live in `src/styles/motion.ts`. Apply via `className` + the shared helpers — never edit `lib/*` or `routes.tsx`.

---

## HARD RULES (every agent)

1. **LIGHT MODE ONLY.** No dark mode, no `dark:` variants, no theme toggle. `color-scheme: light`. Backgrounds stay light; text stays dark and high-contrast.
2. **Preserve all functionality & contracts.** Presentation only (markup / layout / styling / motion). Do **not** change data fetching, API calls, types, auth, routes, permission gates, or form validation. Do **not** edit `lib/api.ts`, `lib/types.ts`, `lib/auth.tsx`, `lib/queryClient.ts`, `lib/api.test.ts`, `lib/auth-contract.test.ts`, `routes.tsx`. Keep every prop, handler, query, mutation, toast, error/loading/empty state, and permission check.
3. **Accessibility (WCAG AA).** Body text >= 4.5:1, large/bold (>=18.66px) >= 3:1 — verified against the **busiest/most-saturated** region of the backdrop, not the average. Visible focus rings, semantic HTML, aria labels, keyboard operability. Respect `prefers-reduced-motion` and `prefers-reduced-transparency`.
4. **No new deps** beyond the declared `framer-motion` and `@fontsource-variable/*`. No other chart/animation libs.
5. Match existing import style and the `@/` path alias. Read files before editing.

---

## Color tokens

Defined in `tailwind.config.ts → theme.extend.colors`.

### Accents (aurora)
| Token | Hex | Use |
|---|---|---|
| `brand-600` / `indigo-600` | `#4f46e5` | Primary action (preserved `brand` alias re-points here) |
| `indigo-500` | `#6366f1` | Aurora indigo, gradient start, charts |
| `violet-500` | `#8b5cf6` | Companion accent |
| `violet-600` | `#7c3aed` | Gradient end |
| `cyan-400` | `#22d3ee` | Cyan accent, charts |
| `aurora.indigo/violet/fuchsia/cyan/sky` | `#6366f1` `#a855f7` `#d946ef` `#22d3ee` `#38bdf8` | Mesh blob hues (decorative, low alpha only) |

### Text (`ink`) — pair with slate equivalents
| Token | Hex | Use |
|---|---|---|
| `text-ink` | `#0f172a` (slate-900) | Headings / primary body |
| `text-ink-soft` | `#334155` (slate-700) | Body |
| `text-ink-muted` | `#475569` (slate-600) | Secondary (AA on >=0.55 glass) |
| `text-ink-faint` | `#64748b` (slate-500) | Large/muted text, placeholders, icons |
| `ink-ghost` `#94a3b8` (slate-400) | **decorative only — never meaningful text** |

### Surface
`surface.canvas #f6f7fb` (page base) · `surface.base #ffffff` · `surface.subtle #f1f5f9` · `surface.line #e2e8f0`.

### Semantic (light tones; always pair with icon/label, never color-alone)
`success` (emerald) · `warn` (amber) · `danger` (rose) · `info` (blue). Each exposes `50/100/200/500/600/700`. Existing Badge tones map: success→emerald, warning→amber, danger→rose, info→brand/indigo.

### Gradients (`bg-*`)
- `bg-aurora-primary` — `linear-gradient(135deg,#6366f1,#7c3aed)` (primary button)
- `bg-aurora-primary-hover` — darker variant
- `bg-aurora-chip` — soft `indigo/cyan @15%` icon-chip fill
- `bg-aurora-text` — for gradient display text (use sparingly; keep AA)

---

## Typography

- `font-sans` → **Inter Variable** (UI/body). Set on `body` with `font-feature-settings:'cv11','ss01'`.
- `font-display` → **Sora Variable** (h1/h2, PageHeader title, big KPI numbers). `h1,h2` get `-0.02em` tracking.
- `font-mono` → JetBrains Mono fallback chain (keys/IDs/fingerprints — unchanged in meaning).
- Metric numerals: use `tabular-nums` + the `text-metric` / `text-metric-sm` / `text-metric-lg` scale for KPI values.

Family names **must** include the `Variable` suffix (`"Inter Variable"`, `"Sora Variable"`) or they silently fall back. Imported in `main.tsx`, never in `lib/*`.

---

## Glass surfaces

Reach for the `@layer components` helpers in `index.css` (they carry the `-webkit-` prefix + all three a11y gates). Compose with Tailwind utilities for radius/padding.

| Helper | Opacity | Blur | Use |
|---|---|---|---|
| `.glass` / `.glass-card` | 0.70 white | 14px sat 170% | Cards/panels with text |
| `.glass-solid` | 0.85 white | 12px sat 150% | Data tables / forms (legibility-critical) |
| `.glass-nav` | 0.60 white | 18px sat 160% | Sidebar / topbar chrome |
| `.glass-sticky` | 0.80 white | 16px sat 160% | Sticky headers (content scrolls under — needs z-index) |
| `.glass-pop` | 0.90 white | 12px sat 160% | Tooltips / dropdowns / floating |

**Canonical card class (copy-paste):**
```
rounded-2xl border border-white/60 bg-white/70 backdrop-blur-glass backdrop-saturate-150 shadow-glass ring-1 ring-slate-900/5
```
Or simply `className="glass-card"` for the full recipe incl. inset rim-of-light. Tables/forms: bump to `bg-white/85` (or `.glass-solid`).

**Rules:** floor glass at `/55` opacity (`/70` for text panels, `/80–85` for tables/forms). Never put meaningful text on a `<0.55` surface. Soft border = `border-white/60` + the inset top highlight (avoid a hard pure-white ring). Cap overlapping backdrop-filter layers at ~2 per region; prefer `.glass-solid` for dense tables.

---

## Elevation (boxShadow tokens)

Layered, Stripe/Linear-style. Reference as `shadow-*`.

| Token | Use |
|---|---|
| `shadow-glass-sm` | Subtle (inputs, small chips) |
| `shadow-glass` | Default card resting |
| `shadow-glass-lg` | Card hover / raised |
| `shadow-glass-xl` | Dialogs / popovers |
| `shadow-glass-inset` | The 1px rim-of-light (baked into `.glass*`) |
| `shadow-glow` / `shadow-glow-lg` | Indigo glow for primary gradient button |

Hover lift = transition `transform` + swap to a **precomputed larger shadow** (`shadow-glass` → `shadow-glass-lg`). Never animate `box-shadow` blur or `backdrop-filter` directly.

---

## Radii & blur

- Radii: inputs/buttons `rounded-md`(0.5)/`rounded-lg`(0.75); cards/panels `rounded-2xl`(1rem); pills/avatars `rounded-full`. Don't `rounded-2xl` dense tables — use `rounded-xl` outer.
- Backdrop blur: `backdrop-blur-glass`(14px), `backdrop-blur-nav`(18px). Sweet spot 12–18px; never blur tiny elements.

---

## Aurora backdrop

Lives on `body::before` (radial mesh) + `body::after` (soft blurred blobs), `position:fixed`, `z-index:-1`, `pointer-events:none`, GPU-promoted. Low-chroma so dark text on glass stays AA. Drifts via `aurora-drift` only under `prefers-reduced-motion: no-preference`. **Do not** put the mesh inside cards; do not re-implement it per page.

---

## Motion system

Tokens & variants in `src/styles/motion.ts` (framer) and `tailwind.config.ts` (CSS). `<MotionConfig reducedMotion="user">` wraps the app in `main.tsx`; `index.css` has a CSS-only reduced-motion net.

**Principle:** animate `transform` + `opacity` only — never width/height/top/left/margin or `backdrop-filter`. Reserve orchestrated motion for first paint + navigation; keep interactive feedback <=150ms. Don't re-run entrance/stagger on TanStack refetches (anchor `initial`/`animate` to mount). Don't animate recharts internals (built-in) — only the wrapping card on entrance.

### Durations (`DURATION.*` s / tailwind `duration-*` ms)
`instant 0.1` · `fast 0.15` (hover/press/focus) · `base 0.22` (most entrances, tabs) · `moderate 0.32` (route, dialog) · `slow 0.5` (ambient only). Stagger step 0.05s, cap cascade ~0.4s.

### Easing (`EASE.*` arrays / tailwind `ease-*`)
`out [0.22,1,0.36,1]` (entrances) · `emphasized [0.16,1,0.3,1]` (dialogs/route) · `in [0.64,0,0.78,0]` (exits) · `standard [0.4,0,0.2,1]` (layout). `linear` only for spinners/progress/ambient.

### Springs (`SPRING.*`)
`snappy {400,30,0.8}` (hover-lift) · `gentle {260,26}` (layout) · `bouncy {500,18}` (success only). Never use spring defaults (they're floaty).

### Variants / recipes (import from `@/styles/motion`)
- `fadeRise` — default entrance `{opacity:0,y:12}→{opacity:1,y:0}` @ base/out.
- `staggerContainer` — parent for KPI grids / lists (50ms step).
- `pageTransition` — wrap `<Outlet/>`; small `y:8`, emphasized ease. Avoid `mode="wait"` with slow durations (doubles perceived nav time). AnimatePresence + `key={location.pathname}` must wrap the Outlet, not live in a page.
- `hoverLift` — `whileHover {y:-2,scale:1.01}` `whileTap {scale:0.98}` + snappy spring (cap scale<=1.02, lift<=4px).
- `overlayPanel` / `overlayBackdrop` — Dialog/dropdown/toast presence.

### CSS-only (Tailwind `animation`/`motion-safe:`) — cheaper interactions
`animate-fade-up` (320ms entrance) · `animate-fade-in` · `animate-scale-in` (dialog) · `animate-shimmer` / `.skeleton` (loading) · `animate-aurora-drift` · `animate-float` · `animate-pulse-ring`. Gate one-off keyframes behind `motion-safe:` when not framer-managed. Standard hover: `transition-[transform,box-shadow,colors] duration-200 ease-out-quint`.

---

## State treatments

- **Loading:** skeleton shimmer rows sized to the real layout (`.skeleton` or `animate-pulse bg-slate-200/60 rounded`) inside cards/tables; keep the existing `Spinner` for buttons and `PageLoader`. Preserve every existing loading branch.
- **Empty:** glass card centered, soft gradient icon circle (`bg-aurora-chip`), one-line headline + `text-ink-muted` helper + the existing CTA only (invent no new actions).
- **Error:** keep existing `apiErrorMessage` text; wrap in a rose-tinted glass note `bg-danger-50/70 border border-danger-200 text-danger-700 rounded-xl`.

---

## Focus & a11y gates (already in index.css)

- Global `:focus-visible` → `0 0 0 2px #fff, 0 0 0 4px rgba(99,102,241,.6)`. Component primitives may upgrade to `focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white` — never remove focus styles.
- `@supports not (backdrop-filter)` → solid `.glass*` at ~0.94.
- `@media (prefers-reduced-transparency: reduce)` → solid white, no blur.
- `@media (prefers-reduced-motion: reduce)` → kill animation/transition + freeze aurora.
- Always ship `-webkit-backdrop-filter` alongside `backdrop-filter` (baked into the helpers).

---

## Performance guardrails

Animate transform/opacity only. Fade glass surfaces in rather than animating blur. `will-change` only on actively-animating elements. Cap overlapping blurred layers at ~2 (prefer `.glass-solid` for dense data). Don't double-animate recharts. Prefer `AnimatePresence mode="popLayout"` for long lists; cap stagger cascades.
