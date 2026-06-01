import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiErrorMessage,
  licenses,
  subscriptions as subsApi,
} from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  Dialog,
  Field,
  Input,
  PageLoader,
  StatusBadge,
} from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import type { License } from '@/lib/types';

export function SubscriptionDetailPage() {
  const { subId = '' } = useParams<{ subId: string }>();
  const qc = useQueryClient();
  const toast = useToast();

  const subQ = useQuery({
    queryKey: ['subscription', subId],
    queryFn: () => subsApi.get(subId),
    enabled: !!subId,
  });
  const licsQ = useQuery({
    queryKey: ['licenses', 'sub', subId],
    queryFn: () => licenses.listForSubscription(subId),
    enabled: !!subId,
  });

  const [issueOpen, setIssueOpen] = useState(false);
  const [ttlDays, setTtlDays] = useState<string>('');
  const [confirmAction, setConfirmAction] = useState<'suspend' | 'cancel' | null>(null);

  const suspendMut = useMutation({
    mutationFn: () => subsApi.suspend(subId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['subscription', subId] });
      toast.success('Subscription suspended');
      setConfirmAction(null);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const cancelMut = useMutation({
    mutationFn: () => subsApi.cancel(subId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['subscription', subId] });
      toast.success('Subscription cancelled');
      setConfirmAction(null);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const issueMut = useMutation({
    mutationFn: async () => {
      const ttl = ttlDays ? Number(ttlDays) : undefined;
      return licenses.issue(subId, ttl ? { ttlDays: ttl } : undefined);
    },
    onSuccess: async (lic) => {
      qc.invalidateQueries({ queryKey: ['licenses', 'sub', subId] });
      setIssueOpen(false);
      setTtlDays('');
      toast.success('License issued');
      if (confirm('Download the license now?')) {
        try {
          const { blob, filename } = await licenses.download(lic.jti);
          triggerDownload(blob, filename);
        } catch (e) {
          toast.error(`Download failed: ${apiErrorMessage(e)}`);
        }
      }
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

  const onRevoke = async (jti: string) => {
    const reason = prompt('Reason for revocation? (optional)') ?? undefined;
    try {
      await licenses.revoke(jti, reason || undefined);
      qc.invalidateQueries({ queryKey: ['licenses', 'sub', subId] });
      toast.success('License revoked');
    } catch (e) {
      toast.error(apiErrorMessage(e));
    }
  };

  if (subQ.isLoading) return <PageLoader />;
  if (subQ.isError) return <div className="text-rose-600">{apiErrorMessage(subQ.error)}</div>;
  const sub = subQ.data!;

  const licenseColumns: Column<License>[] = [
    {
      key: 'jti',
      header: 'JTI',
      render: (l) => <code className="text-xs">{l.jti.slice(0, 16)}…</code>,
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
      render: (l) => new Date(l.issuedAt).toLocaleString(),
    },
    {
      key: 'expires',
      header: 'Expires',
      render: (l) => new Date(l.expiresAt).toLocaleDateString(),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (l) => (
        <div className="flex justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={() => onDownload(l.jti)}>
            Download
          </Button>
          {!l.revokedAt && (
            <PermissionGate permission="license.revoke">
              <Button variant="ghost" size="sm" onClick={() => onRevoke(l.jti)}>
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
        title={`Subscription · ${sub.planName ?? sub.planCode ?? sub.planId}`}
        description={`Organization: ${sub.orgName ?? sub.orgId}`}
        breadcrumb={
          <Link to={`/orgs/${sub.orgId}`} className="hover:text-brand-700">
            {sub.orgName ?? sub.orgId}
          </Link>
        }
        actions={
          <>
            <Link to={`/subscriptions/${subId}/usage`}>
              <Button variant="outline">View usage</Button>
            </Link>
            {sub.status === 'ACTIVE' && (
              <PermissionGate permission="subscription.suspend">
                <Button variant="outline" onClick={() => setConfirmAction('suspend')}>
                  Suspend
                </Button>
              </PermissionGate>
            )}
            {sub.status !== 'CANCELLED' && (
              <PermissionGate permission="subscription.cancel">
                <Button variant="danger" onClick={() => setConfirmAction('cancel')}>
                  Cancel
                </Button>
              </PermissionGate>
            )}
            <PermissionGate permission="license.issue">
              <Button onClick={() => setIssueOpen(true)}>Issue license</Button>
            </PermissionGate>
          </>
        }
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card>
          <CardHeader title="Overview" />
          <CardBody>
            <dl className="space-y-3 text-sm">
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Status</dt>
                <dd>
                  <StatusBadge status={sub.status} />
                </dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Seats</dt>
                <dd className="font-medium">{sub.seats}</dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Starts</dt>
                <dd>{new Date(sub.startsAt).toLocaleDateString()}</dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Ends</dt>
                <dd>{new Date(sub.endsAt).toLocaleDateString()}</dd>
              </div>
              {sub.createdAt && (
                <div className="flex items-center justify-between">
                  <dt className="text-slate-500">Created</dt>
                  <dd>{new Date(sub.createdAt).toLocaleDateString()}</dd>
                </div>
              )}
            </dl>
          </CardBody>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader title="Overrides" description="Per-subscription deltas applied on top of the plan." />
          <CardBody>
            {(!sub.overrides || sub.overrides.length === 0) ? (
              <div className="text-sm text-slate-500">No overrides — uses plan defaults.</div>
            ) : (
              <ul className="space-y-2 text-sm">
                {sub.overrides.map((o, i) => (
                  <li key={i} className="flex items-center gap-2">
                    <Badge tone="info">{o.type}</Badge>
                    <code className="text-xs">{o.key}</code>
                    {o.value !== undefined ? (
                      <span className="text-slate-500"> = {String(o.value)}</span>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>
      </div>

      <div className="mt-6">
        <Card>
          <CardHeader
            title="Licenses"
            description="Issued JWTs for this subscription."
            actions={
              <PermissionGate permission="license.issue">
                <Button size="sm" onClick={() => setIssueOpen(true)}>
                  Issue license
                </Button>
              </PermissionGate>
            }
          />
          <DataTable
            rows={licsQ.data}
            columns={licenseColumns}
            rowKey={(l) => l.jti}
            loading={licsQ.isLoading}
            empty={licsQ.isError ? apiErrorMessage(licsQ.error) : 'No licenses issued yet.'}
          />
        </Card>
      </div>

      <Dialog
        open={issueOpen}
        onClose={() => setIssueOpen(false)}
        title="Issue license"
        description="A new JWT will be signed and a .lic file made available for download."
        footer={
          <>
            <Button variant="outline" onClick={() => setIssueOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => issueMut.mutate()} loading={issueMut.isPending}>
              Issue
            </Button>
          </>
        }
      >
        <Field
          label="TTL (days)"
          htmlFor="ttl"
          hint="Defaults to the plan's TTL if blank"
        >
          <Input
            id="ttl"
            type="number"
            min={1}
            value={ttlDays}
            onChange={(e) => setTtlDays(e.target.value)}
            placeholder="e.g. 365"
          />
        </Field>
      </Dialog>

      <Dialog
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        title={confirmAction === 'suspend' ? 'Suspend subscription?' : 'Cancel subscription?'}
        description={
          confirmAction === 'suspend'
            ? 'The subscription will stop enforcing entitlements. Existing licenses remain valid until expiry unless also revoked.'
            : 'The subscription will be marked cancelled. Existing licenses remain valid until expiry unless also revoked.'
        }
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmAction(null)}>
              Cancel
            </Button>
            {confirmAction === 'suspend' ? (
              <Button onClick={() => suspendMut.mutate()} loading={suspendMut.isPending}>
                Confirm suspend
              </Button>
            ) : (
              <Button
                variant="danger"
                onClick={() => cancelMut.mutate()}
                loading={cancelMut.isPending}
              >
                Confirm cancel
              </Button>
            )}
          </>
        }
      />
    </div>
  );
}
