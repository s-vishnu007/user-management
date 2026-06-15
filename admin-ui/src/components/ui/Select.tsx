import { forwardRef, type SelectHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

/**
 * AURORA GLASS select. Matches the Input glass treatment with a custom indigo
 * chevron so the control reads as part of the same family. Native <select>
 * semantics and all passed props/children are preserved.
 */
const chevron =
  "bg-[length:1.1rem] bg-[right_0.6rem_center] bg-no-repeat pr-9 " +
  "bg-[url('data:image/svg+xml;charset=utf-8,%3Csvg%20xmlns=%27http://www.w3.org/2000/svg%27%20fill=%27none%27%20viewBox=%270%200%2024%2024%27%20stroke-width=%272%27%20stroke=%27%23475569%27%3E%3Cpath%20stroke-linecap=%27round%27%20stroke-linejoin=%27round%27%20d=%27m6%209%206%206%206-6%27/%3E%3C/svg%3E')]";

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { className, invalid, children, ...rest },
  ref,
) {
  return (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        'block w-full appearance-none rounded-lg border bg-white/70 px-3 py-2 text-sm text-ink shadow-glass-sm transition-colors duration-fast focus:outline-none focus:bg-white focus:ring-2',
        invalid
          ? 'border-danger-400 focus:border-danger-400 focus:ring-danger-400/40'
          : 'border-slate-200/80 focus:border-indigo-400 focus:ring-indigo-500/40',
        chevron,
        className,
      )}
      {...rest}
    >
      {children}
    </select>
  );
});
