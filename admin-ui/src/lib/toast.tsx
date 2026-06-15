import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { cn } from '@/lib/cn';
import { toastSlide } from '@/lib/motion';

const TOAST_TTL_MS = 4500;

type ToastKind = 'success' | 'error' | 'info';
interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastCtx {
  push: (msg: string, kind?: ToastKind) => void;
  success: (msg: string) => void;
  error: (msg: string) => void;
  info: (msg: string) => void;
}

const Ctx = createContext<ToastCtx | undefined>(undefined);

let nextId = 1;

/**
 * Decorative line-icons per kind. Inline SVG (matching the AppShell convention)
 * so no new icon dependency is introduced; color comes from `currentColor`.
 */
const KIND_ICONS: Record<ToastKind, ReactNode> = {
  success: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="m8.5 12 2.5 2.5 4.5-5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  error: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="m9 9 6 6m0-6-6 6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  info: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5m0-8h.01" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
};

/**
 * Per-kind glass styling. Semantic colors are always paired with an icon (never
 * color-alone) and kept opaque enough for WCAG AA over the aurora backdrop.
 */
const KIND_STYLES: Record<ToastKind, { surface: string; iconClass: string; label: string }> = {
  success: { surface: 'border-success-200 text-success-700', iconClass: 'text-success-600', label: 'Success' },
  error: { surface: 'border-danger-200 text-danger-700', iconClass: 'text-danger-600', label: 'Error' },
  info: { surface: 'border-info-200 text-info-700', iconClass: 'text-info-600', label: 'Info' },
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  // Per-toast dismiss timers + the wall-clock deadline so the countdown can be
  // paused on hover/focus and resumed with the remaining time (WCAG 2.2.1).
  const timers = useRef<Map<number, { handle: ReturnType<typeof setTimeout>; remaining: number; startedAt: number }>>(
    new Map(),
  );

  const dismiss = useCallback((id: number) => {
    const entry = timers.current.get(id);
    if (entry) {
      clearTimeout(entry.handle);
      timers.current.delete(id);
    }
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const scheduleDismiss = useCallback(
    (id: number, ms: number) => {
      const handle = setTimeout(() => dismiss(id), ms);
      timers.current.set(id, { handle, remaining: ms, startedAt: Date.now() });
    },
    [dismiss],
  );

  // Pause the countdown — keep the remaining time so resume can continue it.
  const pauseDismiss = useCallback((id: number) => {
    const entry = timers.current.get(id);
    if (!entry) return;
    clearTimeout(entry.handle);
    const elapsed = Date.now() - entry.startedAt;
    timers.current.set(id, {
      handle: entry.handle,
      remaining: Math.max(0, entry.remaining - elapsed),
      startedAt: entry.startedAt,
    });
  }, []);

  const resumeDismiss = useCallback(
    (id: number) => {
      const entry = timers.current.get(id);
      if (!entry) return;
      scheduleDismiss(id, entry.remaining);
    },
    [scheduleDismiss],
  );

  const push = useCallback(
    (message: string, kind: ToastKind = 'info') => {
      const id = nextId++;
      setToasts((prev) => [...prev, { id, message, kind }]);
      scheduleDismiss(id, TOAST_TTL_MS);
    },
    [scheduleDismiss],
  );

  // Clear any pending timers on unmount.
  useEffect(() => {
    const map = timers.current;
    return () => {
      for (const entry of map.values()) clearTimeout(entry.handle);
      map.clear();
    };
  }, []);

  const value = useMemo<ToastCtx>(
    () => ({
      push,
      success: (m) => push(m, 'success'),
      error: (m) => push(m, 'error'),
      info: (m) => push(m, 'info'),
    }),
    [push],
  );

  return (
    <Ctx.Provider value={value}>
      {children}
      {/* Neutral wrapper — no aria-live here. Each toast is its own live region
          (role="alert" for errors = assertive, role="status" otherwise = polite),
          which avoids nesting an assertive region inside a polite one. */}
      <div
        className="pointer-events-none fixed inset-x-4 top-4 z-50 flex flex-col gap-2 sm:inset-x-auto sm:right-4 sm:left-auto sm:max-w-sm"
        role="region"
        aria-label="Notifications"
      >
        <AnimatePresence initial={false}>
          {toasts.map((t) => {
            const style = KIND_STYLES[t.kind];
            return (
              <motion.div
                key={t.id}
                layout
                variants={toastSlide}
                initial="hidden"
                animate="show"
                exit="exit"
                role={t.kind === 'error' ? 'alert' : 'status'}
                tabIndex={0}
                // Timing-adjustable: pause the auto-dismiss while the user hovers
                // or keyboard-focuses the toast so it can be read/re-read.
                onMouseEnter={() => pauseDismiss(t.id)}
                onMouseLeave={() => resumeDismiss(t.id)}
                onFocus={() => pauseDismiss(t.id)}
                onBlur={() => resumeDismiss(t.id)}
                className={cn(
                  'glass-pop pointer-events-auto flex items-start gap-3 rounded-xl px-4 py-3 text-sm',
                  'border shadow-glass-lg ring-1 ring-slate-900/5',
                  'focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white',
                  style.surface,
                )}
              >
                <span
                  className={cn('mt-0.5 h-5 w-5 shrink-0 [&>svg]:h-5 [&>svg]:w-5', style.iconClass)}
                  aria-hidden="true"
                >
                  {KIND_ICONS[t.kind]}
                </span>
                <div className="min-w-0 flex-1">
                  <span className="sr-only">{style.label}: </span>
                  <span className="font-medium leading-snug">{t.message}</span>
                </div>
              </motion.div>
            );
          })}
        </AnimatePresence>
      </div>
    </Ctx.Provider>
  );
}

export function useToast(): ToastCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useToast must be used inside ToastProvider');
  return v;
}
