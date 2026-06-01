import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';

const toneClasses: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  warning: 'bg-amber-50 text-amber-700 ring-amber-200',
  danger: 'bg-rose-50 text-rose-700 ring-rose-200',
  info: 'bg-brand-50 text-brand-700 ring-brand-200',
};

export function Badge({
  tone = 'neutral',
  className,
  ...rest
}: HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        toneClasses[tone],
        className,
      )}
      {...rest}
    />
  );
}

export function StatusBadge({ status }: { status?: string }) {
  if (!status) return null;
  const s = status.toUpperCase();
  let tone: Tone = 'neutral';
  if (s === 'ACTIVE') tone = 'success';
  else if (s === 'SUSPENDED' || s === 'EXPIRED' || s === 'PENDING') tone = 'warning';
  else if (s === 'CANCELLED' || s === 'DISABLED' || s === 'REVOKED' || s === 'RETIRED') tone = 'danger';
  else if (s === 'DRAFT' || s === 'INVITED') tone = 'info';
  return <Badge tone={tone}>{s}</Badge>;
}
