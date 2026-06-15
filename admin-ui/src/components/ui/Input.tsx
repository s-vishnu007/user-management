import {
  cloneElement,
  forwardRef,
  isValidElement,
  type InputHTMLAttributes,
  type ReactElement,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/cn';

/**
 * AURORA GLASS form fields. Frosted-glass surface at rest that crisps to solid
 * white on focus with an indigo ring. Invalid state keeps the rose treatment.
 * Transition is transform/opacity/color-free of layout, <=150ms.
 */
const fieldBase =
  'block w-full rounded-lg border bg-white/70 px-3 py-2 text-sm text-ink shadow-glass-sm transition-colors duration-fast focus:outline-none focus:bg-white focus:ring-2';
const fieldValid =
  'border-slate-200/80 focus:border-indigo-400 focus:ring-indigo-500/40';
const fieldInvalid =
  'border-danger-400 focus:border-danger-400 focus:ring-danger-400/40';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, invalid, ...rest },
  ref,
) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        fieldBase,
        invalid ? fieldInvalid : fieldValid,
        'placeholder:text-ink-faint',
        className,
      )}
      {...rest}
    />
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, invalid, ...rest },
  ref,
) {
  return (
    <textarea
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        fieldBase,
        invalid ? fieldInvalid : fieldValid,
        'placeholder:text-ink-faint',
        className,
      )}
      {...rest}
    />
  );
});

export function Label({
  htmlFor,
  children,
  required,
  hint,
}: {
  htmlFor?: string;
  children: React.ReactNode;
  required?: boolean;
  hint?: React.ReactNode;
}) {
  return (
    <div className="mb-1 flex items-center justify-between">
      <label htmlFor={htmlFor} className="text-xs font-medium text-ink-soft">
        {children}
        {required ? <span className="ml-0.5 text-danger-500">*</span> : null}
      </label>
      {hint ? <span className="text-xs text-ink-faint">{hint}</span> : null}
    </div>
  );
}

export function FieldError({ id, children }: { id?: string; children?: React.ReactNode }) {
  if (!children) return null;
  return (
    <p id={id} className="mt-1 text-xs text-danger-600">
      {children}
    </p>
  );
}

export function Field({
  label,
  htmlFor,
  required,
  hint,
  error,
  children,
}: {
  label: React.ReactNode;
  htmlFor?: string;
  required?: boolean;
  hint?: React.ReactNode;
  error?: React.ReactNode;
  children: React.ReactNode;
}) {
  const errorId = error && htmlFor ? `${htmlFor}-error` : undefined;
  const describedChild =
    errorId && isValidElement(children)
      ? cloneElement(children as ReactElement<Record<string, unknown>>, {
          'aria-describedby':
            [
              (children as ReactElement<Record<string, unknown>>).props['aria-describedby'],
              errorId,
            ]
              .filter(Boolean)
              .join(' ') || undefined,
          'aria-errormessage': errorId,
        })
      : children;
  return (
    <div>
      <Label htmlFor={htmlFor} required={required} hint={hint}>
        {label}
      </Label>
      {describedChild}
      <FieldError id={errorId}>{error}</FieldError>
    </div>
  );
}
