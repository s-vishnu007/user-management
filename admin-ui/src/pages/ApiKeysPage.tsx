import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { apiErrorMessage, apiKeys } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Badge, Button, Dialog, Field, Input } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import type { ApiKey } from '@/lib/types';

const schema = z.object({
  name: z.string().min(2),
  scopes: z.string().min(1, 'At least one scope is required'),
});
type Values = z.infer<typeof schema>;

export function ApiKeysPage() {
  const { orgId = '' } = useParams<{ orgId: string }>();
  const qc = useQueryClient();
  const toast = useToast();
  const [openCreate, setOpenCreate] = useState(false);
  const [createdKey, setCreatedKey] = useState<ApiKey | null>(null);

  const keysQ = useQuery({
    queryKey: ['org', orgId, 'api-keys'],
    queryFn: () => apiKeys.list(orgId),
    enabled: !!orgId,
  });

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', scopes: 'license.read,license.issue' },
  });

  const createMut = useMutation({
    mutationFn: (v: Values) =>
      apiKeys.create(orgId, {
        name: v.name,
        scopes: v.scopes
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
      }),
    onSuccess: (k) => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'api-keys'] });
      setOpenCreate(false);
      form.reset({ name: '', scopes: 'license.read,license.issue' });
      setCreatedKey(k);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => apiKeys.remove(orgId, id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'api-keys'] });
      toast.success('API key revoked');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const columns: Column<ApiKey>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (k) => <span className="font-medium text-ink">{k.name}</span>,
    },
    {
      key: 'prefix',
      header: 'Prefix',
      render: (k) => (
        <code className="rounded-md bg-slate-100/80 px-1.5 py-0.5 font-mono text-xs text-ink-soft ring-1 ring-inset ring-slate-900/5">
          {k.prefix}…
        </code>
      ),
    },
    {
      key: 'scopes',
      header: 'Scopes',
      render: (k) => (
        <div className="flex flex-wrap gap-1">
          {k.scopes.map((s) => (
            <Badge key={s} tone="info" className="font-mono">
              {s}
            </Badge>
          ))}
        </div>
      ),
    },
    {
      key: 'created',
      header: 'Created',
      render: (k) => (
        <span className="tabular-nums text-ink-muted">{new Date(k.createdAt).toLocaleDateString()}</span>
      ),
    },
    {
      key: 'lastUsed',
      header: 'Last used',
      render: (k) => (
        <span className="tabular-nums text-ink-muted">
          {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (k) =>
        k.revokedAt ? (
          <Badge tone="danger">
            <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-danger-500" />
            Revoked
          </Badge>
        ) : (
          <Badge tone="success">
            <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-success-500" />
            Active
          </Badge>
        ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (k) => (
        <PermissionGate permission="api-key.delete">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              if (confirm(`Revoke API key "${k.name}"?`)) deleteMut.mutate(k.id);
            }}
          >
            Revoke
          </Button>
        </PermissionGate>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="API keys"
        description="Programmatic access to the control plane API for CI pipelines and integrations."
        breadcrumb={
          <Link
            to={`/orgs/${orgId}`}
            className="rounded transition-colors hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
          >
            Organization
          </Link>
        }
        actions={
          <PermissionGate permission="api-key.create">
            <Button onClick={() => setOpenCreate(true)}>New API key</Button>
          </PermissionGate>
        }
      />

      <DataTable
        rows={keysQ.data}
        columns={columns}
        rowKey={(k) => k.id}
        loading={keysQ.isLoading}
        empty={keysQ.isError ? apiErrorMessage(keysQ.error) : 'No API keys yet.'}
      />

      <Dialog
        open={openCreate}
        onClose={() => setOpenCreate(false)}
        title="Create API key"
        description="The plaintext key will be shown only once after creation."
        footer={
          <>
            <Button variant="outline" onClick={() => setOpenCreate(false)}>
              Cancel
            </Button>
            <Button form="ak-form" type="submit" loading={createMut.isPending}>
              Create
            </Button>
          </>
        }
      >
        <form id="ak-form" onSubmit={form.handleSubmit((v) => createMut.mutate(v))} className="space-y-4">
          <Field label="Name" htmlFor="ak-name" required error={form.formState.errors.name?.message}>
            <Input id="ak-name" {...form.register('name')} />
          </Field>
          <Field
            label="Scopes (comma-separated)"
            htmlFor="ak-scopes"
            required
            error={form.formState.errors.scopes?.message}
            hint="e.g. license.read,license.issue"
          >
            <Input id="ak-scopes" {...form.register('scopes')} />
          </Field>
        </form>
      </Dialog>

      <Dialog
        open={createdKey !== null}
        onClose={() => setCreatedKey(null)}
        title="API key created"
        description="Copy this key now. You won't be able to see it again."
        footer={
          <Button onClick={() => setCreatedKey(null)}>Close</Button>
        }
      >
        {createdKey && (
          <div className="space-y-4">
            <div
              role="alert"
              className="flex items-start gap-3 rounded-xl border border-warn-200 bg-warn-50/80 px-3.5 py-3 text-sm text-warn-700"
            >
              <span
                aria-hidden
                className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-warn-100 text-warn-700"
              >
                <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4">
                  <path
                    fillRule="evenodd"
                    d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 8a1 1 0 100-2 1 1 0 000 2z"
                    clipRule="evenodd"
                  />
                </svg>
              </span>
              <span>
                <span className="font-semibold">Store this in a secret manager.</span> We cannot
                recover it — this is the only time it will be shown.
              </span>
            </div>

            <div className="rounded-xl border border-white/70 bg-white/85 p-3 shadow-glass-inset ring-1 ring-slate-900/5">
              <div className="mb-1.5 text-xs font-medium uppercase tracking-wide text-ink-faint">
                Secret key
              </div>
              <pre className="overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-50/90 p-3 font-mono text-xs leading-relaxed text-ink ring-1 ring-inset ring-slate-900/5">
                {createdKey.plaintext ?? `${createdKey.prefix}...`}
              </pre>
            </div>

            <Button
              onClick={() => {
                if (createdKey.plaintext) {
                  navigator.clipboard.writeText(createdKey.plaintext).then(
                    () => toast.success('Copied to clipboard'),
                    () => toast.error('Copy failed'),
                  );
                }
              }}
            >
              <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden className="h-4 w-4">
                <path d="M7 3a2 2 0 00-2 2v8a2 2 0 002 2h6a2 2 0 002-2V7.414A2 2 0 0014.414 6L12 3.586A2 2 0 0010.586 3H7z" />
                <path d="M3 7a2 2 0 012-2v9a2 2 0 002 2h6a2 2 0 01-2 2H5a2 2 0 01-2-2V7z" />
              </svg>
              Copy key
            </Button>
          </div>
        )}
      </Dialog>
    </div>
  );
}
