/**
 * AURORA GLASS — motion convenience barrel.
 *
 * The canonical motion source of truth lives in `@/styles/motion`. This module
 * simply RE-EXPORTS those tokens/variants so feature code can import shared
 * animation primitives from a single, ergonomic `@/lib/motion` path alongside
 * the other `@/lib/*` utilities — without ever duplicating (and risking
 * divergence of) the underlying values.
 *
 * It also adds a couple of small, additive presentation-only variants that are
 * reusable across pages (toast/notification slide-in) but did not belong in the
 * lower-level styles module. All variants animate transform + opacity only and
 * inherit the project's reduced-motion handling (MotionConfig + index.css net).
 */
export {
  DURATION,
  EASE,
  SPRING,
  fadeRise,
  staggerContainer,
  pageTransition,
  hoverLift,
  hoverLiftBold,
  overlayPanel,
  overlayBackdrop,
  dialogPanel,
  dialogContent,
  dialogItem,
  tableStagger,
  tableRow,
  successPop,
  chartReveal,
} from '@/styles/motion';

import type { Variants } from 'framer-motion';
import { DURATION, EASE } from '@/styles/motion';

/**
 * Notification / toast presence — slide in from the trailing (right) edge with a
 * gentle scale settle, accelerate out. Use inside <AnimatePresence>.
 */
export const toastSlide: Variants = {
  hidden: { opacity: 0, x: 24, scale: 0.96 },
  show: {
    opacity: 1,
    x: 0,
    scale: 1,
    transition: { duration: DURATION.base, ease: EASE.emphasized },
  },
  exit: {
    opacity: 0,
    x: 24,
    scale: 0.98,
    transition: { duration: DURATION.fast, ease: EASE.in },
  },
};
