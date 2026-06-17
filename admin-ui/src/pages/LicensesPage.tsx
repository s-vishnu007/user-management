import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { apiErrorMessage, licenses, orgs } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  Field,
  Input,
  PageLoader,
  Select,
  StatusBadge,
  Textarea,
} from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useAuth } from '@/lib/auth';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import { fadeRise, staggerContainer } from '@/lib/motion';
import type { IssuedLicense, License } from '@/lib/types';

type StatusFilter = 'all' | 'active' | 'expired' | 'revoked';

export function LicensesPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const { hasPermission } = useAuth();
  // Only users who can actually issue see the issue workspace + load the grant catalog (the
  // /assignable-grants endpoint is gated on license.issue, so fetching it without the permission
  // would 403). View-only users (license.read) still see the org-scoped license list below.
  const canIssueLicenses = hasPermission('license.issue');

  // Issue-form state — the workspace issues a JWT to a USER inside an ORG with a hand-picked RBAC
  // grant set (roles as presets, individual permissions on top). No plan / subscription involved.
  const [orgId, setOrgId] = useState('');
  const [email, setEmail] = useState('');
  const [selectedRoles, setSelectedRoles] = useState<Set<string>>(new Set());
  const [selectedPerms, setSelectedPerms] = useState<Set<string>>(new Set());
  // Permissions the user toggled ON individually (not via a role). Tracked so that DESELECTING a role
  // never strips a permission the user explicitly added — see toggleRole's `keep` set.
  const [manualPerms, setManualPerms] = useState<Set<string>>(new Set());
  const [ttlDays, setTtlDays] = useState('');
  const [trial, setTrial] = useState(false);
  const [audience, setAudience] = useState('');
  const [issued, setIssued] = useState<IssuedLicense | null>(null);

  // List filters.
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');

  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });
  const grantsQ = useQuery({
    queryKey: ['license-grants'],
    queryFn: licenses.assignableGrants,
    enabled: canIssueLicenses,
  });
  const membersQ = useQuery({
    queryKey: ['org', orgId, 'members'],
    queryFn: () => orgs.members(orgId),
    enabled: !!orgId,
  });
  const licsQ = useQuery({
    queryKey: ['licenses', 'org', orgId],
    queryFn: () => licenses.listForOrg(orgId),
    enabled: !!orgId,
  });

  // Switching org clears the just-issued result (it belonged to the previous scope).
  useEffect(() => {
    setIssued(null);
  }, [orgId]);

  const rolePerms = (code: string): string[] =>
    grantsQ.data?.roles.find((r) => r.code === code)?.permissions ?? [];

  // Toggling a role preset adds (or removes) exactly its permissions. On removal, a permission is
  // kept if another still-selected role grants it OR the user added it individually (manualPerms) —
  // so deselecting a role never strips a permission the admin explicitly chose.
  const toggleRole = (code: string) => {
    const turningOn = !selectedRoles.has(code);
    const nextRoles = new Set(selectedRoles);
    if (turningOn) nextRoles.add(code);
    else nextRoles.delete(code);

    const nextPerms = new Set(selectedPerms);
    const thisPerms = rolePerms(code);
    if (turningOn) {
      thisPerms.forEach((p) => nextPerms.add(p));
    } else {
      const keep = new Set<string>(manualPerms);
      nextRoles.forEach((rc) => rolePerms(rc).forEach((p) => keep.add(p)));
      thisPerms.forEach((p) => {
        if (!keep.has(p)) nextPerms.delete(p);
      });
    }
    setSelectedRoles(nextRoles);
    setSelectedPerms(nextPerms);
  };

  const togglePerm = (code: string) => {
    const turningOn = !selectedPerms.has(code);
    setSelectedPerms((prev) => {
      const next = new Set(prev);
      if (turningOn) next.add(code);
      else next.delete(code);
      return next;
    });
    // Track individual intent so a later role-deselect doesn't remove a hand-picked permission.
    setManualPerms((prev) => {
      const next = new Set(prev);
      if (turningOn) next.add(code);
      else next.delete(code);
      return next;
    });
  };

  const clearGrants = () => {
    setSelectedRoles(new Set());
    setSelectedPerms(new Set());
    setManualPerms(new Set());
  };

  // Permissions grouped by category for a scannable picker.
  const groupedPerms = useMemo(() => {
    const map = new Map<string, { code: string; name?: string; description?: string }[]>();
    (grantsQ.data?.permissions ?? []).forEach((p) => {
      const cat = p.category || 'other';
      if (!map.has(cat)) map.set(cat, []);
      map.get(cat)!.push(p);
    });
    return [...map.entries()];
  }, [grantsQ.data]);

  const issueMut = useMutation({
    mutationFn: () =>
      licenses.issueForOrg(orgId, {
        email: email.trim() || undefined,
        roleCodes: [...selectedRoles],
        permissions: [...selectedPerms],
        ttlDays: ttlDays ? Number(ttlDays) : undefined,
        trial,
        audience: audience.trim()
          ? audience.split(',').map((s) => s.trim()).filter(Boolean)
          : undefined,
      }),
    onSuccess: (lic) => {
      setIssued(lic);
      qc.invalidateQueries({ queryKey: ['licenses', 'org', orgId] });
      toast.success('License issued');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const revokeMut = useMutation({
    mutationFn: ({ jti, reason }: { jti: string; reason?: string }) => licenses.revoke(jti, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['licenses', 'org', orgId] });
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

  const copyJwt = async () => {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(issued.license);
      toast.success('JWT copied to clipboard');
    } catch {
      toast.error('Copy failed — select and copy manually');
    }
  };

  const data = useMemo(() => {
    const now = new Date();
    return (licsQ.data ?? []).filter((l) => {
      if (search) {
        const hay = `${l.subjectEmail ?? ''} ${l.jti} ${(l.permissions ?? []).join(' ')}`.toLowerCase();
        if (!hay.includes(search.toLowerCase())) return false;
      }
      if (statusFilter === 'all') return true;
      const revoked = !!l.revokedAt;
      const expired = new Date(l.expiresAt) < now;
      if (statusFilter === 'revoked') return revoked;
      if (statusFilter === 'expired') return !revoked && expired;
      if (statusFilter === 'active') return !revoked && !expired;
      return true;
    });
  }, [licsQ.data, search, statusFilter]);

  const canIssue = !!orgId && !!email.trim();

  const columns: Column<License>[] = [
    {
      key: 'subject',
      header: 'Issued to',
      render: (l) => {
        const isSubscriptionLicense = !l.userId && !!l.subscriptionId;
        return (
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <span className="truncate font-medium text-ink">
                {l.subjectEmail ?? (isSubscriptionLicense ? 'Subscription license' : '—')}
              </span>
              {isSubscriptionLicense && <Badge tone="neutral">subscription</Badge>}
            </div>
            <code className="font-mono text-[11px] text-ink-faint">{l.jti.slice(0, 16)}…</code>
          </div>
        );
      },
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
      key: 'grants',
      header: 'Grants',
      render: (l) => {
        // Subscription/legacy licenses carry no RBAC snapshot — their entitlements live in the plan.
        if (!l.userId && !!l.subscriptionId) {
          return <span className="text-xs text-ink-faint">plan-based</span>;
        }
        const perms = l.permissions ?? [];
        const roles = l.roles ?? [];
        return (
          <div className="flex flex-wrap items-center gap-1">
            {roles.map((r) => (
              <Badge key={r} tone="info">
                {r}
              </Badge>
            ))}
            <span
              className="text-xs text-ink-muted"
              title={perms.length ? perms.join(', ') : 'No permissions'}
            >
              {perms.length} {perms.length === 1 ? 'permission' : 'permissions'}
            </span>
          </div>
        );
      },
    },
    {
      key: 'issued',
      header: 'Issued',
      render: (l) => (
        <span className="tabular-nums text-ink-soft">{new Date(l.issuedAt).toLocaleDateString()}</span>
      ),
    },
    {
      key: 'expires',
      header: 'Expires',
      render: (l) => (
        <span className="tabular-nums text-ink-soft">{new Date(l.expiresAt).toLocaleDateString()}</span>
      ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (l) => (
        <div className="flex justify-end gap-1">
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
    ? 'Select an organization to view its licenses.'
    : licsQ.isError
      ? apiErrorMessage(licsQ.error)
      : 'No licenses issued in this organization yet.';

  return (
    <div>
      <PageHeader
        title="Licenses"
        description="Issue a signed JWT license to a user in an organization, with a hand-picked RBAC grant set."
      />

      <motion.div variants={staggerContainer} initial="hidden" animate="show">
        {/* ---- Issue workspace (only for users who can issue) ---- */}
        <PermissionGate permission="license.issue">
        <motion.div variants={fadeRise}>
          <Card className="mb-6">
            <CardHeader
              title="Issue a license"
              description="Pick the organization and the user, choose exactly what to grant, then mint the token."
            />
            <CardBody className="space-y-5">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
                  label="Issue to (user)"
                  htmlFor="lic-user"
                  hint={orgId ? 'Pick a member or type a new email to invite' : 'Pick an organization first'}
                >
                  <Input
                    id="lic-user"
                    type="email"
                    list="lic-member-emails"
                    placeholder="user@example.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    disabled={!orgId}
                  />
                  <datalist id="lic-member-emails">
                    {(membersQ.data ?? []).map((m) => (
                      <option key={m.userId} value={m.email}>
                        {m.fullName ? `${m.fullName} · ${m.role}` : m.role}
                      </option>
                    ))}
                  </datalist>
                </Field>
              </div>

              {/* Grant picker */}
              <div className="rounded-xl border border-slate-200/80 bg-white/40 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <h4 className="text-sm font-semibold text-ink">RBAC grants</h4>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-ink-muted">{selectedPerms.size} selected</span>
                    {(selectedPerms.size > 0 || selectedRoles.size > 0) && (
                      <Button variant="ghost" size="sm" onClick={clearGrants}>
                        Clear
                      </Button>
                    )}
                  </div>
                </div>

                {grantsQ.isLoading ? (
                  <PageLoader />
                ) : grantsQ.isError ? (
                  <div className="rounded-lg border border-danger-200 bg-danger-50/70 px-3 py-2 text-sm text-danger-700">
                    {apiErrorMessage(grantsQ.error)}
                  </div>
                ) : (
                  <>
                    {/* Role presets */}
                    <div className="mb-4">
                      <div className="mb-1.5 text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Role presets
                      </div>
                      <div className="flex flex-wrap gap-2">
                        {(grantsQ.data?.roles ?? []).map((r) => {
                          const on = selectedRoles.has(r.code);
                          return (
                            <button
                              key={r.code}
                              type="button"
                              onClick={() => toggleRole(r.code)}
                              title={`${r.permissions.length} permissions`}
                              className={
                                'rounded-full border px-3 py-1 text-xs font-medium transition-colors ' +
                                (on
                                  ? 'border-indigo-300 bg-indigo-50 text-indigo-700'
                                  : 'border-slate-200 bg-white/70 text-ink-muted hover:bg-white')
                              }
                            >
                              {r.name ?? r.code}
                            </button>
                          );
                        })}
                      </div>
                    </div>

                    {/* Individual permissions, grouped by category */}
                    <div className="space-y-3">
                      {groupedPerms.map(([cat, perms]) => (
                        <div key={cat}>
                          <div className="mb-1.5 text-xs font-medium uppercase tracking-wide text-ink-faint">
                            {cat}
                          </div>
                          <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2 lg:grid-cols-3">
                            {perms.map((p) => (
                              <label
                                key={p.code}
                                className="flex cursor-pointer items-start gap-2 rounded-lg px-2 py-1.5 hover:bg-white/70"
                                title={p.description}
                              >
                                <input
                                  type="checkbox"
                                  className="mt-0.5 h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                                  checked={selectedPerms.has(p.code)}
                                  onChange={() => togglePerm(p.code)}
                                />
                                <span className="min-w-0">
                                  <code className="font-mono text-xs text-ink">{p.code}</code>
                                </span>
                              </label>
                            ))}
                          </div>
                        </div>
                      ))}
                    </div>
                  </>
                )}
              </div>

              {/* Token options */}
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <Field label="TTL (days)" htmlFor="lic-ttl" hint="Defaults to 365">
                  <Input
                    id="lic-ttl"
                    type="number"
                    min={1}
                    value={ttlDays}
                    onChange={(e) => setTtlDays(e.target.value)}
                    placeholder="365"
                  />
                </Field>
                <Field label="Audience" htmlFor="lic-aud" hint="Optional, comma-separated">
                  <Input
                    id="lic-aud"
                    value={audience}
                    onChange={(e) => setAudience(e.target.value)}
                    placeholder="docker-app-prod"
                  />
                </Field>
                <Field label="Type" htmlFor="lic-trial">
                  <label className="flex h-[38px] items-center gap-2 text-sm text-ink-soft">
                    <input
                      id="lic-trial"
                      type="checkbox"
                      className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                      checked={trial}
                      onChange={(e) => setTrial(e.target.checked)}
                    />
                    Trial license (short TTL)
                  </label>
                </Field>
              </div>

              <div className="flex items-center justify-end gap-3">
                {!canIssue && (
                  <span className="text-xs text-ink-faint">
                    Select an organization and a user to enable issuing.
                  </span>
                )}
                <PermissionGate permission="license.issue">
                  <Button
                    onClick={() => issueMut.mutate()}
                    loading={issueMut.isPending}
                    disabled={!canIssue || issueMut.isPending}
                  >
                    Issue JWT license
                  </Button>
                </PermissionGate>
              </div>

              {/* Issued result */}
              {issued && (
                <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-4">
                  <div className="mb-2 flex items-center justify-between">
                    <h4 className="text-sm font-semibold text-emerald-800">License issued</h4>
                    <button
                      type="button"
                      className="text-xs text-ink-muted hover:text-ink"
                      onClick={() => setIssued(null)}
                    >
                      Dismiss
                    </button>
                  </div>
                  <dl className="mb-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-ink-soft sm:grid-cols-4">
                    <div>
                      <dt className="text-ink-faint">JTI</dt>
                      <dd className="truncate font-mono">{issued.jti}</dd>
                    </div>
                    <div>
                      <dt className="text-ink-faint">Key</dt>
                      <dd className="truncate font-mono">{issued.kid}</dd>
                    </div>
                    <div>
                      <dt className="text-ink-faint">Expires</dt>
                      <dd className="tabular-nums">{new Date(issued.expiresAt).toLocaleDateString()}</dd>
                    </div>
                  </dl>
                  <Textarea readOnly rows={3} value={issued.license} className="font-mono text-xs" />
                  <div className="mt-2 flex gap-2">
                    <Button size="sm" variant="outline" onClick={copyJwt}>
                      Copy JWT
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => onDownload(issued.jti)}>
                      Download .lic
                    </Button>
                  </div>
                </div>
              )}
            </CardBody>
          </Card>
        </motion.div>
        </PermissionGate>

        {/* ---- Issued licenses for the selected org ---- */}
        <motion.div variants={fadeRise}>
          <DataTable
            rows={orgId ? data : []}
            columns={columns}
            rowKey={(l) => l.jti}
            loading={!!orgId && licsQ.isLoading}
            empty={emptyMessage}
            toolbar={
              <div className="flex w-full flex-wrap items-center gap-2">
                <Input
                  placeholder="Search by user, JTI or permission"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
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
