import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { apiErrorMessage, audit } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { fadeRise, staggerContainer } from '@/lib/motion';
import type { AuditEntry } from '@/lib/types';

export function AuditPage() {
  const [filters, setFilters] = useState({
    actorId: '',
    action: '',
    targetType: '',
    from: '',
    to: '',
  });
  const [applied, setApplied] = useState(filters);

  const auditQ = useQuery({
    queryKey: ['audit', applied],
    queryFn: () => audit.list({ ...applied, page: 1, pageSize: 100 }),
  });

  const items = useMemo(() => auditQ.data?.items ?? [], [auditQ.data]);

  const columns: Column<AuditEntry>[] = [
    {
      key: 'when',
      header: 'When',
      width: '15rem',
      render: (a) => (
        <span className="whitespace-nowrap tabular-nums text-ink-soft">
          {new Date(a.occurredAt).toLocaleString()}
        </span>
      ),
    },
    {
      key: 'actor',
      header: 'Actor',
      render: (a) => (
        <span className="font-medium text-ink">{a.actorEmail ?? a.actorId ?? '—'}</span>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (a) => (
        <code className="inline-flex items-center rounded-md bg-aurora-chip px-1.5 py-0.5 font-mono text-xs text-indigo-700 ring-1 ring-inset ring-indigo-500/15">
          {a.action}
        </code>
      ),
    },
    {
      key: 'target',
      header: 'Target',
      render: (a) =>
        a.targetType ? (
          <span className="text-ink-soft">
            <span className="text-ink-faint">{a.targetType}</span>
            <span className="mx-1 text-ink-ghost">·</span>
            <span className="font-mono text-xs text-ink-muted">{a.targetId ?? ''}</span>
          </span>
        ) : (
          <span className="text-ink-faint">—</span>
        ),
    },
    {
      key: 'ip',
      header: 'IP',
      render: (a) =>
        a.ip ? (
          <span className="font-mono text-xs text-ink-muted">{a.ip}</span>
        ) : (
          <span className="text-ink-faint">—</span>
        ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Audit log"
        description="Every write in Keyforge produces an audit entry. Immutable, append-only."
      />

      {/* Page body cascade — anchored to this wrapper so a background audit
          refetch (new query params apply on Apply only) does not replay it. */}
      <motion.div variants={staggerContainer} initial="hidden" animate="show">
        <motion.div variants={fadeRise}>
          <Card className="mb-4">
            <CardBody className="grid grid-cols-1 gap-3 sm:grid-cols-2 md:grid-cols-3 xl:grid-cols-6 xl:items-end">
              <Field label="Actor" htmlFor="filter-actor">
              <Input
                id="filter-actor"
                placeholder="Email or ID"
                value={filters.actorId}
                onChange={(e) => setFilters((f) => ({ ...f, actorId: e.target.value }))}
              />
            </Field>
            <Field label="Action" htmlFor="filter-action">
              <Input
                id="filter-action"
                placeholder="e.g. license.issued"
                value={filters.action}
                onChange={(e) => setFilters((f) => ({ ...f, action: e.target.value }))}
              />
            </Field>
            <Field label="Target type" htmlFor="filter-target">
              <Input
                id="filter-target"
                placeholder="e.g. subscription"
                value={filters.targetType}
                onChange={(e) => setFilters((f) => ({ ...f, targetType: e.target.value }))}
              />
            </Field>
            <Field label="From" htmlFor="filter-from">
              <Input
                id="filter-from"
                type="date"
                value={filters.from}
                onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value }))}
              />
            </Field>
            <Field label="To" htmlFor="filter-to">
              <Input
                id="filter-to"
                type="date"
                value={filters.to}
                onChange={(e) => setFilters((f) => ({ ...f, to: e.target.value }))}
              />
            </Field>
            <Button className="w-full justify-center" onClick={() => setApplied(filters)}>
              Apply
            </Button>
            </CardBody>
          </Card>
        </motion.div>

        <motion.div variants={fadeRise}>
          <DataTable
            rows={items}
            columns={columns}
            rowKey={(a) => a.id}
            loading={auditQ.isLoading}
            empty={auditQ.isError ? apiErrorMessage(auditQ.error) : 'No audit events.'}
            pageSize={50}
          />
        </motion.div>
      </motion.div>
    </div>
  );
}
