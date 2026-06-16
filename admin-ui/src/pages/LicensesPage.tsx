import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { apiErrorMessage, licenses, orgs, subscriptions } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Card, CardBody, Field, Input, Select, StatusBadge } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import { fadeRise, staggerContainer } from '@/lib/motion';
import type { License } from '@/lib/types';

type StatusFilter = 'all' | 'active' | 'expired' | 'revoked';

export function LicensesPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const [filter, setFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');

  // The /licenses endpoint requires a subscriptionId (the tenant-leak fix), so a global
  // unscoped list is no longer possible. The admin picks an org, then a subscription, and we
  // list that subscription's licenses. (Flagged: a scoped backend aggregate endpoint would let
  // us show a true cross-subscription view — see crossCuttingNotes.)
  const [orgId, setOrgId] = useState('');
  const [subId, setSubId] = useState('');

  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });
  const subsQ = useQuery({
    queryKey: ['org', orgId, 'subscriptions'],
    queryFn: () => subscriptions.listForOrg(orgId),
    enabled: !!orgId,
  });

  // Reset the selected subscription whenever the org changes.
  useEffect(() => {
    setSubId('');
  }, [orgId]);

  const licsQ = useQuery({
    queryKey: ['licenses', 'sub', subId],
    queryFn: () => licenses.listForSubscription(subId),
    enabled: !!subId,
  });

  const data = useMemo(() => {
    const now = new Date();
    return (licsQ.data ?? []).filter((l) => {
      if (filter) {
        const hay = `${l.jti} ${l.kid ?? ''}`.toLowerCase();
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
      qc.invalidateQueries({ queryKey: ['licenses', 'sub', subId] });
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
      render: (l) => (
        <code className="font-mono text-xs text-ink-soft">{l.jti.slice(0, 18)}…</code>
      ),
    },
    {
      key: 'kid',
      header: 'Key',
      render: (l) => <code className="font-mono text-xs text-ink-muted">{l.kid}</code>,
    },
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
      render: (l) => (
        <span className="tabular-nums text-ink-soft">
          {new Date(l.issuedAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: 'expires',
      header: 'Expires',
      render: (l) => (
        <span className="tabular-nums text-ink-soft">
          {new Date(l.expiresAt).toLocaleDateString()}
        </span>
      ),
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
          {/* Revoked licenses cannot be downloaded (the backend rejects them), so hide the action. */}
          {!l.revokedAt && (
            <Button variant="ghost" size="sm" onClick={() => onDownload(l.jti)}>
              Download
            </Button>
          )}
          {!l.revokedAt && (
            <PermissionGate permission="license.revoke">
              <Button
                variant="ghost"
                size="sm"
                className="text-danger-600 hover:bg-danger-50/70 hover:text-danger-700"
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

  const emptyMessage = !orgId
    ? 'Select an organization to begin.'
    : !subId
      ? 'Select a subscription to view its licenses.'
      : licsQ.isError
        ? apiErrorMessage(licsQ.error)
        : 'No licenses found for this subscription.';

  return (
    <div>
      <PageHeader
        title="Licenses"
        description="Issued license tokens, scoped to a subscription."
      />

      {/* Page body: choreograph the scope picker + results table in with a gentle
          stagger. Anchored to this wrapper (kept mounted across query refetches),
          so background license refetches do NOT replay the entrance cascade. */}
      <motion.div variants={staggerContainer} initial="hidden" animate="show">
        {/* Scope picker — the org→subscription drill-down promoted into its own
            glass panel so the workspace reads as a deliberate two-step flow. */}
        <motion.div variants={fadeRise}>
          <Card className="mb-6">
            <CardBody className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Organization" htmlFor="lic-org">
                <Select
                  id="lic-org"
                  value={orgId}
                  onChange={(e) => setOrgId(e.target.value)}
                  disabled={orgsQ.isLoading}
                >
                  <option value="">Select organization…</option>
                  {(orgsQ.data ?? []).map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name} ({o.slug})
                    </option>
                  ))}
                </Select>
              </Field>
              <Field
                label="Subscription"
                htmlFor="lic-sub"
                hint={orgId ? undefined : 'Pick an organization first'}
              >
                <Select
                  id="lic-sub"
                  value={subId}
                  onChange={(e) => setSubId(e.target.value)}
                  disabled={!orgId || subsQ.isLoading}
                >
                  <option value="">Select subscription…</option>
                  {(subsQ.data ?? []).map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.planName ?? s.planCode ?? s.planId} · {s.status}
                    </option>
                  ))}
                </Select>
              </Field>
            </CardBody>
          </Card>
        </motion.div>

        <motion.div variants={fadeRise}>
          <DataTable
            rows={subId ? data : []}
            columns={columns}
            rowKey={(l) => l.jti}
            loading={!!subId && licsQ.isLoading}
            empty={emptyMessage}
            toolbar={
              <div className="flex w-full flex-wrap items-center gap-2">
                <Input
                  placeholder="Search by JTI or key"
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
        </motion.div>
      </motion.div>
    </div>
  );
}
