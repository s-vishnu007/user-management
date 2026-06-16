import type { ReactNode } from 'react';

export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumb?: ReactNode;
}) {
  // No built-in entrance here: the route transition (AppShell) animates the whole page in, and pages
  // that wrap the header in a fadeRise stagger drive it from there. Having both produced a double
  // Y-drift on the header (transitions re-audit). One entrance per element.
  return (
    <div className="mb-6">
      {breadcrumb ? (
        <div className="mb-2 text-xs font-medium text-ink-muted">{breadcrumb}</div>
      ) : null}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">{title}</h1>
          {description ? <p className="mt-1.5 text-sm text-ink-muted">{description}</p> : null}
        </div>
        {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
      </div>
    </div>
  );
}
