/**
 * AURORA GLASS — motion tokens & reusable framer-motion variants.
 *
 * Single source of truth for animation. Import these instead of re-deriving
 * durations/easings/variants per component. All values respect the project's
 * accessibility rule: <MotionConfig reducedMotion="user"> (wired in main.tsx)
 * strips transform/layout animation for users who request reduced motion, and
 * index.css has a CSS-only reduced-motion safety net.
 *
 * Principle: animate ONLY transform (translate/scale) and opacity. Never
 * width/height/top/left/margin (layout thrash, worse over the glass blur).
 * Reserve orchestrated motion (route, stagger, presence) for first paint and
 * navigation; keep interactive feedback fast (<=150ms) and minimal.
 */
import type { Transition, Variants } from 'framer-motion';

/**
 * Durations in seconds (framer). These drive every entrance/route/dialog/table animation.
 * Tuned slower for a more deliberate, cinematic feel (interactive hover/press is spring-based,
 * see SPRING, so it stays snappy regardless of these values).
 */
export const DURATION = {
  instant: 0.14,
  fast: 0.24,
  base: 0.42,
  moderate: 0.6,
  slow: 0.9,
} as const;

/** Cubic-bezier easing arrays (framer `ease`). */
export const EASE = {
  /** standard entrances — snappy then soft (easeOutQuint). */
  out: [0.22, 1, 0.36, 1],
  /** emphasized — dialogs / route (easeOutExpo). */
  emphasized: [0.16, 1, 0.3, 1],
  /** exits / accelerate. */
  in: [0.64, 0, 0.78, 0],
  /** layout shifts — Material standard inOut. */
  standard: [0.4, 0, 0.2, 1],
} as const;

/** Spring presets (duration-less, interruptible — ideal for hover/press/layout). */
export const SPRING = {
  /** button / card hover-lift. */
  snappy: { type: 'spring', stiffness: 400, damping: 30, mass: 0.8 } as Transition,
  /** layout / reorder. */
  gentle: { type: 'spring', stiffness: 260, damping: 26 } as Transition,
  /** success accents only — use sparingly. */
  bouncy: { type: 'spring', stiffness: 500, damping: 18 } as Transition,
} as const;

/** Default entrance for cards / sections / page content. */
export const fadeRise: Variants = {
  hidden: { opacity: 0, y: 12 },
  show: {
    opacity: 1,
    y: 0,
    transition: { duration: DURATION.base, ease: EASE.out },
  },
};

/** Parent container that staggers fadeRise children (KPI grids, card lists, nav). */
export const staggerContainer: Variants = {
  hidden: {},
  show: {
    transition: { staggerChildren: 0.08, delayChildren: 0.06 },
  },
};

/**
 * Route transition wrapper (wrap <Outlet/>, used by AppShell with `mode="wait"`).
 *
 * ┌─ THREE STYLES — pick one to test ─────────────────────────────────────────┐
 * │ Exactly ONE `export const pageTransition` may be active. To try another,   │
 * │ COMMENT OUT the active block and UNCOMMENT one of the others — they all     │
 * │ expose the same {initial, animate, exit, transition} shape AppShell reads,  │
 * │ so nothing else changes. Save → Vite HMR swaps it live; click between pages │
 * │ to feel it.                                                                 │
 * └────────────────────────────────────────────────────────────────────────────┘
 */

// ── STYLE 1 · "Bold & cinematic" ────────────────────────────────────────────
// Directional rise layered with a subtle scale cross-dissolve — navigation reads
// as a deliberate scene change. Scale stays >=0.99 to avoid blur jank over glass.
// export const pageTransition = {
//   initial: { opacity: 0, y: 16, scale: 0.99 },
//   animate: { opacity: 1, y: 0, scale: 1 },
//   exit: { opacity: 0, y: -12, scale: 0.992 },
//   transition: { duration: DURATION.base, ease: EASE.emphasized },
// } as const;

// ── STYLE 2 · "Minimal fade" (ACTIVE) ────────────────────────────────────────
// Pure opacity cross-fade, no movement. The calmest option — content feels like
// it's simply "already there". (Using the slower `base` duration for a gentle
// dissolve; swap to DURATION.fast if you want it quicker.)
export const pageTransition = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: { duration: DURATION.base, ease: EASE.standard },
} as const;

// ── STYLE 3 · "Cinematic zoom + blur" ───────────────────────────────────────
// The most theatrical: each page swells up from slightly-small and blurred into
// crisp focus, the old one zooms past. NOTE: animating `filter: blur` over the
// glass backdrop costs more than transform/opacity — if you see jank on low-end
// GPUs, delete the two `filter` lines and keep the scale. (reduced-motion users
// still get a plain fade via MotionConfig + the index.css safety net.)
// export const pageTransition = {
//   initial: { opacity: 0, scale: 0.95, filter: 'blur(8px)' },
//   animate: { opacity: 1, scale: 1, filter: 'blur(0px)' },
//   exit: { opacity: 0, scale: 1.04, filter: 'blur(6px)' },
//   transition: { duration: DURATION.slow, ease: EASE.emphasized },
// } as const;

/** Hover-lift for glass cards/buttons — the DYNAMIC signature. Restraint = premium. */
export const hoverLift = {
  whileHover: { y: -2, scale: 1.01 },
  whileTap: { scale: 0.98 },
  transition: SPRING.snappy,
} as const;

/** Bolder, more theatrical hover-lift for feature cards / interactive panels. */
export const hoverLiftBold = {
  whileHover: { y: -4, scale: 1.02 },
  whileTap: { scale: 0.97 },
  transition: SPRING.snappy,
} as const;

/** Overlay (Dialog / dropdown / toast) panel presence — use with AnimatePresence. */
export const overlayPanel: Variants = {
  hidden: { opacity: 0, scale: 0.96, y: 8 },
  show: {
    opacity: 1,
    scale: 1,
    y: 0,
    transition: { duration: DURATION.base, ease: EASE.emphasized },
  },
  exit: {
    opacity: 0,
    scale: 0.98,
    y: 4,
    transition: { duration: DURATION.fast, ease: EASE.in },
  },
};

/** Backdrop fade for overlays. */
export const overlayBackdrop: Variants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { duration: DURATION.base } },
  exit: { opacity: 0, transition: { duration: DURATION.fast } },
};

/**
 * Dialog panel presence — a bigger, spring-driven pop than {@link overlayPanel}
 * for a more theatrical entrance. Pair with {@link overlayBackdrop} (bloom) and,
 * optionally, {@link dialogContent}/{@link dialogItem} to choreograph the body in.
 */
export const dialogPanel: Variants = {
  hidden: { opacity: 0, scale: 0.92, y: 16 },
  show: {
    opacity: 1,
    scale: 1,
    y: 0,
    transition: { type: 'spring', stiffness: 360, damping: 28, mass: 0.9 },
  },
  exit: {
    opacity: 0,
    scale: 0.97,
    y: 8,
    transition: { duration: DURATION.fast, ease: EASE.in },
  },
};

/** Stagger container for a dialog's header/body/footer (choreograph in after the pop). */
export const dialogContent: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.05, delayChildren: 0.06 } },
};

/** Item inside {@link dialogContent}. */
export const dialogItem: Variants = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0, transition: { duration: DURATION.base, ease: EASE.out } },
};

/**
 * Table body: cascade rows in on first paint / page change. Apply to a
 * motion.tbody (initial="hidden" animate="show"); give each row {@link tableRow}.
 * Anchored to mount, so a same-data refetch (rows keep their keys) does NOT replay.
 */
export const tableStagger: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.035, delayChildren: 0.02 } },
};

/** A single cascading table row. Transform+opacity only (no layout thrash). */
export const tableRow: Variants = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0, transition: { duration: DURATION.base, ease: EASE.out } },
};

/** Success celebration — a small bouncy pop for confirmation checkmarks/badges. */
export const successPop: Variants = {
  hidden: { opacity: 0, scale: 0.6 },
  show: { opacity: 1, scale: 1, transition: SPRING.bouncy },
};

/** Card / chart reveal — fade + rise + subtle scale for chart & panel first paint. */
export const chartReveal: Variants = {
  hidden: { opacity: 0, y: 16, scale: 0.985 },
  show: {
    opacity: 1,
    y: 0,
    scale: 1,
    transition: { duration: DURATION.moderate, ease: EASE.emphasized },
  },
};
