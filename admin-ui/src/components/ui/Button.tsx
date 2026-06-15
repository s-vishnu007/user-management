import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'outline';
type Size = 'sm' | 'md' | 'lg';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

/**
 * AURORA GLASS button variants.
 * - primary: aurora gradient + indigo glow, hover lift, gradient deepens on hover.
 * - secondary / outline: frosted glass chip that brightens on hover.
 * - ghost: transparent, gains a soft glass wash on hover.
 * - danger: rose action with matching glow.
 * Micro-interactions are CSS-only (transform/opacity, <=150ms) so they respect
 * prefers-reduced-motion via the global net in index.css.
 */
const variantClasses: Record<Variant, string> = {
  primary:
    'bg-aurora-primary text-white shadow-glow hover:bg-aurora-primary-hover hover:shadow-glow-lg hover:-translate-y-px active:translate-y-0 disabled:bg-brand-300 disabled:bg-none disabled:shadow-none disabled:hover:translate-y-0',
  secondary:
    'bg-white/60 text-ink-soft backdrop-blur border border-white/70 ring-1 ring-slate-900/5 shadow-glass-sm hover:bg-white/80 hover:-translate-y-px active:translate-y-0 disabled:bg-white/40 disabled:shadow-none disabled:hover:translate-y-0',
  danger:
    'bg-danger-600 text-white shadow-[0_4px_14px_-4px_rgba(225,29,72,0.5)] hover:bg-danger-700 hover:shadow-[0_6px_20px_-4px_rgba(225,29,72,0.6)] hover:-translate-y-px active:translate-y-0 disabled:bg-danger-200 disabled:shadow-none disabled:hover:translate-y-0',
  ghost:
    'bg-transparent text-ink-soft hover:bg-white/60 active:bg-white/80 disabled:hover:bg-transparent',
  outline:
    'bg-white/60 text-ink-soft backdrop-blur border border-white/70 ring-1 ring-slate-900/5 shadow-glass-sm hover:bg-white/80 hover:-translate-y-px active:translate-y-0 disabled:bg-white/40 disabled:shadow-none disabled:hover:translate-y-0',
};

const sizeClasses: Record<Size, string> = {
  sm: 'px-2.5 py-1.5 text-xs',
  md: 'px-3.5 py-2 text-sm',
  lg: 'px-5 py-2.5 text-base',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant = 'primary', size = 'md', loading, disabled, children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-all duration-fast ease-out-quint focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white disabled:cursor-not-allowed disabled:opacity-70',
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
      disabled={disabled || loading}
      {...rest}
    >
      {loading && (
        <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent" />
      )}
      {children}
    </button>
  );
});
