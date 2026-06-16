import { useEffect, useId, useRef, type ReactNode } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { cn } from '@/lib/cn';
import { dialogContent, dialogItem, dialogPanel, overlayBackdrop } from '@/lib/motion';

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
}: {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  // Remember the element focused before the dialog opened so focus can be
  // restored to the trigger on close (a11y: focus management).
  const restoreFocusRef = useRef<Element | null>(null);

  // Stable ids so the accessible name/description can be announced.
  const reactId = useId();
  const titleId = `dialog-title-${reactId}`;
  const descId = `dialog-desc-${reactId}`;

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Focus management: capture the previously-focused element, move focus into the
  // panel on open, and restore it on close. Runs only on the open->mounted edge.
  useEffect(() => {
    if (!open) return;
    restoreFocusRef.current = document.activeElement;
    const panel = panelRef.current;
    if (panel) {
      const focusable = panel.querySelector<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      // Prefer the first focusable control; fall back to the panel itself.
      (focusable ?? panel).focus();
    }
    return () => {
      const toRestore = restoreFocusRef.current;
      if (toRestore instanceof HTMLElement) toRestore.focus();
    };
  }, [open]);

  const sizeClass = {
    sm: 'max-w-sm',
    md: 'max-w-md',
    lg: 'max-w-2xl',
    xl: 'max-w-4xl',
  }[size];

  // Focus trap: keep Tab / Shift+Tab cycling within the panel so keyboard and
  // screen-reader users cannot reach the obscured page behind the backdrop.
  const onPanelKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Tab') return;
    const panel = panelRef.current;
    if (!panel) return;
    const focusable = Array.from(
      panel.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (focusable.length === 0) {
      // Nothing focusable inside — keep focus on the panel.
      e.preventDefault();
      panel.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const activeEl = document.activeElement;
    if (e.shiftKey) {
      if (activeEl === first || activeEl === panel) {
        e.preventDefault();
        last.focus();
      }
    } else {
      if (activeEl === last) {
        e.preventDefault();
        first.focus();
      }
    }
  };

  // AnimatePresence keeps the panel mounted through its exit animation. The
  // backdrop blooms in, the panel springs up with a pop, and the header/body/
  // footer choreograph in just behind it. MotionConfig (reducedMotion="user")
  // collapses all of this to a plain opacity fade for reduced-motion users.
  return (
    <AnimatePresence>
      {open ? (
        <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto p-4 sm:p-8">
          <motion.div
            className="fixed inset-0 bg-slate-900/40"
            onClick={onClose}
            aria-hidden="true"
            variants={overlayBackdrop}
            initial="hidden"
            animate="show"
            exit="exit"
          />
          <motion.div
            ref={panelRef}
            tabIndex={-1}
            onKeyDown={onPanelKeyDown}
            className={cn(
              'relative z-10 mt-4 sm:mt-12 w-full rounded-2xl bg-white/90 backdrop-blur-glass backdrop-saturate-150',
              'border border-white/70 shadow-glass-xl ring-1 ring-slate-900/5 focus:outline-none',
              sizeClass,
            )}
            role="dialog"
            aria-modal="true"
            aria-labelledby={title ? titleId : undefined}
            aria-describedby={description ? descId : undefined}
            variants={dialogPanel}
            initial="hidden"
            animate="show"
            exit="exit"
          >
            <motion.div variants={dialogContent} initial="hidden" animate="show">
              {title || description ? (
                <motion.div variants={dialogItem} className="border-b border-slate-900/5 px-5 py-4">
                  {title ? (
                    <h3
                      id={titleId}
                      className="font-display text-base font-semibold tracking-tight text-ink"
                    >
                      {title}
                    </h3>
                  ) : null}
                  {description ? (
                    <p id={descId} className="mt-1 text-sm text-ink-muted">
                      {description}
                    </p>
                  ) : null}
                </motion.div>
              ) : null}
              <motion.div variants={dialogItem} className="px-5 py-4">
                {children}
              </motion.div>
              {footer ? (
                <motion.div
                  variants={dialogItem}
                  className="flex items-center justify-end gap-2 border-t border-slate-900/5 bg-white/40 px-5 py-3"
                >
                  {footer}
                </motion.div>
              ) : null}
            </motion.div>
          </motion.div>
        </div>
      ) : null}
    </AnimatePresence>
  );
}
