import { forwardRef, type SelectHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { className, invalid, children, ...rest },
  ref,
) {
  return (
    <select
      ref={ref}
      className={cn(
        'block w-full rounded-md border bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-2',
        invalid
          ? 'border-rose-400 focus:ring-rose-400'
          : 'border-slate-300 focus:border-brand-500 focus:ring-brand-500',
        className,
      )}
      {...rest}
    >
      {children}
    </select>
  );
});
