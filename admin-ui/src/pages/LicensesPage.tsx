import { useMemo, useState } from 'react';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { apiErrorMessage, licenses } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Input, Select, StatusBadge } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import type { License } from '@/lib/types';

type StatusFilter = 'all' | 'active' | 'expired' | 'revoked';

export function LicensesPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const [filter, setFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');

  const licsQ = useQuery({ queryKey: ['licenses'], queryFn: () => licenses.list() });

  const data = useMemo(() => {
    const now = new Date();
    return (licsQ.data ?? []).filter((l) => {
      if (filter) {
        const hay = `${l.jti} ${l.orgName ?? ''} ${l.planCode ?? ''}`.toLowerCase();
        if (!hay.includes(filter.toLowerCase())) return false;
      }
      if (statusFilter === 'all') return true;
      const revoked = !!l.revokedAt;
      const expired = new Date(l.expiresAt) < now;
      if (statusFilter === 'revoked') return revoked;
      if (statusFilter === 'expired') return !revoked && expired;
      if (statusFilter === 'active') return !revoked && !expired;
      return true;
    });
  }, [licsQ.data, filter, statusFilter]);

  const revokeMut = useMutation({
    mutationFn: ({ jti, reason }: { jti: string; reason?: string }) =>
      licenses.revoke(jti, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['licenses'] });
      toast.success('License revoked');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const onDownload = async (jti: string) => {
    try {
      const { blob, filename } = await licenses.download(jti);
      triggerDownload(blob, filename);
    } catch (e) {
      toast.error(apiErrorMessage(e));
    }
  };

  const columns: Column<License>[] = [
    {
      key: 'jti',
      header: 'JTI',
      render: (l) => <code className="text-xs">{l.jti.slice(0, 18)}…</code>,
    },
    {
      key: 'org',
      header: 'Organization',
      render: (l) => l.orgName ?? '—',
    },
    {
      key: 'plan',
      header: 'Plan',
      render: (l) => (l.planCode ? <code className="text-xs">{l.planCode}</code> : '—'),
    },
    { key: 'kid', header: 'Key', render: (l) => <code className="text-xs">{l.kid}</code> },
    {
      key: 'status',
      header: 'Status',
      render: (l) =>
        l.revokedAt ? (
          <StatusBadge status="REVOKED" />
        ) : new Date(l.expiresAt) < new Date() ? (
          <StatusBadge status="EXPIRED" />
        ) : (
          <StatusBadge status="ACTIVE" />
        ),
    },
    {
      key: 'issued',
      header: 'Issued',
      render: (l) => new Date(l.issuedAt).toLocaleDateString(),
    },
    {
      key: 'expires',
      header: 'Expires',
      render: (l) => new Date(l.expiresAt).toLocaleDateString(),
    },
    {
      key: 'sub',
      header: '',
      className: 'text-right',
      render: (l) => (
        <div className="flex justify-end gap-1">
          <Link to={`/subscriptions/${l.subscriptionId}`}>
            <Button variant="ghost" size="sm">
              Subscription
            </Button>
          </Link>
          <Button variant="ghost" size="sm" onClick={() => onDownload(l.jti)}>
            Download
          </Button>
          {!l.revokedAt && (
            <PermissionGate permission="license.revoke">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  const reason = prompt('Reason for revocation? (optional)') ?? undefined;
                  revokeMut.mutate({ jti: l.jti, reason: reason || undefined });
                }}
              >
                Revoke
              </Button>
            </PermissionGate>
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Licenses"
        description="All issued license tokens across all subscriptions."
      />
      <DataTable
        rows={data}
        columns={columns}
        rowKey={(l) => l.jti}
        loading={licsQ.isLoading}
        empty={licsQ.isError ? apiErrorMessage(licsQ.error) : 'No licenses found.'}
        toolbar={
          <div className="flex w-full flex-wrap items-center gap-2">
            <Input
              placeholder="Search by JTI, org, plan"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="max-w-sm"
            />
            <Select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
              className="max-w-xs"
            >
              <option value="all">All statuses</option>
              <option value="active">Active</option>
              <option value="expired">Expired</option>
              <option value="revoked">Revoked</option>
            </Select>
          </div>
        }
      />
    </div>
  );
}
