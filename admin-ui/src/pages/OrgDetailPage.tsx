import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  apiErrorMessage,
  audit as auditApi,
  orgs,
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
  Select,
  Tabs,
} from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { fadeRise, hoverLift, staggerContainer } from '@/lib/motion';
import type { AuditEntry, OrgMember } from '@/lib/types';

const ROLES = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'] as const;
const inviteSchema = z.object({
  email: z.string().email(),
  role: z.enum(ROLES),
});
type InviteValues = z.infer<typeof inviteSchema>;

export function OrgDetailPage() {
  const { orgId = '' } = useParams<{ orgId: string }>();
  const [tab, setTab] = useState<'members' | 'sso' | 'apiKeys' | 'audit'>('members');

  const orgQ = useQuery({ queryKey: ['org', orgId], queryFn: () => orgs.get(orgId), enabled: !!orgId });

  return (
    <div>
      {/* Header + tab bar compose in on navigation; anchored to this always-mounted
          root so a background TanStack refetch does NOT replay the cascade. The per-tab
          section below has its own (intentional) keyed entrance on tab change. */}
      <motion.div variants={staggerContainer} initial="hidden" animate="show">
        <motion.div variants={fadeRise}>
          <PageHeader
            title={orgQ.data?.name ?? 'Organization'}
            description={orgQ.data ? `Slug: ${orgQ.data.slug}` : undefined}
            breadcrumb={
              <Link
                to="/orgs"
                className="rounded-sm text-ink-muted transition-colors hover:text-indigo-700"
              >
                Organizations
              </Link>
            }
            actions={
              orgQ.data ? (
                <>
                  <Link to={`/orgs/${orgId}/sso`}>
                    <Button variant="outline">SSO</Button>
                  </Link>
                  <Link to={`/orgs/${orgId}/api-keys`}>
                    <Button variant="outline">API keys</Button>
                  </Link>
                </>
              ) : null
            }
          />
        </motion.div>

        <motion.div variants={fadeRise}>
          <Tabs
            active={tab}
            onChange={(id) => setTab(id as typeof tab)}
            tabs={[
              { id: 'members', label: 'Members' },
              { id: 'sso', label: 'SSO' },
              { id: 'apiKeys', label: 'API keys' },
              { id: 'audit', label: 'Audit' },
            ]}
          />
        </motion.div>
      </motion.div>

      <motion.div key={tab} variants={fadeRise} initial="hidden" animate="show" className="mt-6">
        {tab === 'members' && <MembersTab orgId={orgId} />}
        {tab === 'sso' && (
          <motion.div {...hoverLift}>
            <Card>
              <CardBody>
                <p className="text-sm text-ink-soft">
                  Configure SAML or OIDC for this organization.
                </p>
                <div className="mt-4">
                  <Link to={`/orgs/${orgId}/sso`}>
                    <Button>Open SSO settings</Button>
                  </Link>
                </div>
              </CardBody>
            </Card>
          </motion.div>
        )}
        {tab === 'apiKeys' && (
          <motion.div {...hoverLift}>
            <Card>
              <CardBody>
                <p className="text-sm text-ink-soft">
                  Manage API keys for programmatic control plane access.
                </p>
                <div className="mt-4">
                  <Link to={`/orgs/${orgId}/api-keys`}>
                    <Button>Open API keys</Button>
                  </Link>
                </div>
              </CardBody>
            </Card>
          </motion.div>
        )}
        {tab === 'audit' && <OrgAuditTab orgId={orgId} />}
      </motion.div>
    </div>
  );
}

function MembersTab({ orgId }: { orgId: string }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [open, setOpen] = useState(false);

  const membersQ = useQuery({
    queryKey: ['org', orgId, 'members'],
    queryFn: () => orgs.members(orgId),
    enabled: !!orgId,
  });

  const form = useForm<InviteValues>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { email: '', role: 'MEMBER' },
  });

  const inviteMut = useMutation({
    mutationFn: (v: InviteValues) => orgs.addMember(orgId, v),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'members'] });
      setOpen(false);
      form.reset({ email: '', role: 'MEMBER' });
      toast.success('Member added');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const removeMut = useMutation({
    mutationFn: (userId: string) => orgs.removeMember(orgId, userId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'members'] });
      toast.success('Member removed');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const columns: Column<OrgMember>[] = [
    {
      key: 'email',
      header: 'Email',
      render: (m) => <span className="font-medium text-ink">{m.email}</span>,
    },
    {
      key: 'name',
      header: 'Name',
      render: (m) => m.displayName ?? <span className="text-ink-faint">—</span>,
    },
    { key: 'role', header: 'Role', render: (m) => <Badge tone="info">{m.role}</Badge> },
    {
      key: 'joinedAt',
      header: 'Joined',
      render: (m) =>
        m.joinedAt ? (
          new Date(m.joinedAt).toLocaleDateString()
        ) : (
          <span className="text-ink-faint">—</span>
        ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (m) => (
        <PermissionGate permission="org.members.remove">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              if (confirm(`Remove ${m.email}?`)) removeMut.mutate(m.userId);
            }}
          >
            Remove
          </Button>
        </PermissionGate>
      ),
    },
  ];

  return (
    <>
      <DataTable
        rows={membersQ.data}
        columns={columns}
        rowKey={(m) => m.userId}
        loading={membersQ.isLoading}
        empty={membersQ.isError ? apiErrorMessage(membersQ.error) : 'No members yet.'}
        toolbar={
          <div className="flex w-full items-center justify-between">
            <span className="text-sm tabular-nums text-ink-muted">
              {membersQ.data?.length ?? 0} members
            </span>
            <PermissionGate permission="org.members.add">
              <Button size="sm" onClick={() => setOpen(true)}>
                Add member
              </Button>
            </PermissionGate>
          </div>
        }
      />
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Add member"
        footer={
          <>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button form="invite-form" type="submit" loading={inviteMut.isPending}>
              Add
            </Button>
          </>
        }
      >
        <form
          id="invite-form"
          onSubmit={form.handleSubmit((v) => inviteMut.mutate(v))}
          className="space-y-4"
        >
          <Field
            label="Email"
            htmlFor="m-email"
            required
            hint="No account yet? They'll be invited (a password-less account is created)."
            error={form.formState.errors.email?.message}
          >
            <Input
              id="m-email"
              type="email"
              {...form.register('email')}
              invalid={!!form.formState.errors.email}
            />
          </Field>
          <Field label="Role" htmlFor="m-role" required error={form.formState.errors.role?.message}>
            <Select id="m-role" {...form.register('role')}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </Select>
          </Field>
        </form>
      </Dialog>
    </>
  );
}

function OrgAuditTab({ orgId }: { orgId: string }) {
  const auditQ = useQuery({
    queryKey: ['org', orgId, 'audit'],
    queryFn: () => auditApi.forOrg(orgId, { page: 1, pageSize: 50 }),
    enabled: !!orgId,
  });

  const items = useMemo(() => auditQ.data?.items ?? [], [auditQ.data]);

  const columns: Column<AuditEntry>[] = [
    {
      key: 'when',
      header: 'When',
      render: (a) => new Date(a.occurredAt).toLocaleString(),
    },
    {
      key: 'actor',
      header: 'Actor',
      render: (a) => a.actorEmail ?? a.actorId ?? <span className="text-ink-faint">—</span>,
    },
    {
      key: 'action',
      header: 'Action',
      render: (a) => (
        <code className="rounded bg-slate-100/70 px-1.5 py-0.5 font-mono text-xs text-ink-soft">
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
            <span className="text-ink-muted">{a.targetType}</span> · {a.targetId ?? ''}
          </span>
        ) : (
          <span className="text-ink-faint">—</span>
        ),
    },
  ];

  return (
    <DataTable
      rows={items}
      columns={columns}
      rowKey={(a) => a.id}
      loading={auditQ.isLoading}
      empty={auditQ.isError ? apiErrorMessage(auditQ.error) : 'No audit events.'}
    />
  );
}
