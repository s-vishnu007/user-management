import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';

/**
 * AURORA GLASS tonal status pills. Each tone pairs a soft tinted fill with a
 * readable AA text color and an inset ring, so meaning never rests on color
 * alone (callers also pass a label). `info` maps to the indigo brand accent.
 */
const toneClasses: Record<Tone, string> = {
  neutral: 'bg-slate-100/80 text-ink-soft ring-slate-300/70',
  success: 'bg-success-50/90 text-success-700 ring-success-200',
  warning: 'bg-warn-50/90 text-warn-700 ring-warn-200',
  danger: 'bg-danger-50/90 text-danger-700 ring-danger-200',
  info: 'bg-brand-50/90 text-brand-700 ring-brand-200',
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
