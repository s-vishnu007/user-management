import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiErrorMessage, audit } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Input } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
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
    { key: 'when', header: 'When', render: (a) => new Date(a.occurredAt).toLocaleString() },
    { key: 'actor', header: 'Actor', render: (a) => a.actorEmail ?? a.actorId ?? '—' },
    {
      key: 'action',
      header: 'Action',
      render: (a) => <code className="text-xs">{a.action}</code>,
    },
    {
      key: 'target',
      header: 'Target',
      render: (a) =>
        a.targetType ? (
          <span>
            <span className="text-slate-500">{a.targetType}</span> · {a.targetId ?? ''}
          </span>
        ) : (
          '—'
        ),
    },
    { key: 'ip', header: 'IP', render: (a) => a.ip ?? '—' },
  ];

  return (
    <div>
      <PageHeader
        title="Audit log"
        description="Every write in the control panel produces an audit entry. Immutable, append-only."
      />

      <div className="mb-4 grid grid-cols-1 gap-2 rounded-lg border border-slate-200 bg-white p-3 md:grid-cols-6">
        <Input
          placeholder="Actor email or ID"
          value={filters.actorId}
          onChange={(e) => setFilters((f) => ({ ...f, actorId: e.target.value }))}
        />
        <Input
          placeholder="Action (e.g. license.issued)"
          value={filters.action}
          onChange={(e) => setFilters((f) => ({ ...f, action: e.target.value }))}
        />
        <Input
          placeholder="Target type"
          value={filters.targetType}
          onChange={(e) => setFilters((f) => ({ ...f, targetType: e.target.value }))}
        />
        <Input
          type="date"
          value={filters.from}
          onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value }))}
        />
        <Input
          type="date"
          value={filters.to}
          onChange={(e) => setFilters((f) => ({ ...f, to: e.target.value }))}
        />
        <Button onClick={() => setApplied(filters)}>Apply</Button>
      </div>

      <DataTable
        rows={items}
        columns={columns}
        rowKey={(a) => a.id}
        loading={auditQ.isLoading}
        empty={auditQ.isError ? apiErrorMessage(auditQ.error) : 'No audit events.'}
        pageSize={50}
      />
    </div>
  );
}
