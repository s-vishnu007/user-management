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

/** Durations in seconds (framer). Mirror of tailwind transitionDuration tokens. */
export const DURATION = {
  instant: 0.1,
  fast: 0.15,
  base: 0.22,
  moderate: 0.32,
  slow: 0.5,
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
    transition: { staggerChildren: 0.05, delayChildren: 0.04 },
  },
};

/** Route transition wrapper (wrap <Outlet/>). Keep y small so nav feels instant. */
export const pageTransition = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -8 },
  transition: { duration: DURATION.base, ease: EASE.emphasized },
} as const;

/** Hover-lift for glass cards/buttons — the DYNAMIC signature. Restraint = premium. */
export const hoverLift = {
  whileHover: { y: -2, scale: 1.01 },
  whileTap: { scale: 0.98 },
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
