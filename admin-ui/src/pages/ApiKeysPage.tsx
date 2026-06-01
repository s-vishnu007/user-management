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
  expiresAt: z.string().optional(),
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
        expiresAt: v.expiresAt || undefined,
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
      render: (k) => <span className="font-medium text-slate-900">{k.name}</span>,
    },
    {
      key: 'prefix',
      header: 'Prefix',
      render: (k) => <code className="text-xs">{k.prefix}…</code>,
    },
    {
      key: 'scopes',
      header: 'Scopes',
      render: (k) => (
        <div className="flex flex-wrap gap-1">
          {k.scopes.map((s) => (
            <Badge key={s} tone="info">
              {s}
            </Badge>
          ))}
        </div>
      ),
    },
    {
      key: 'created',
      header: 'Created',
      render: (k) => new Date(k.createdAt).toLocaleDateString(),
    },
    {
      key: 'lastUsed',
      header: 'Last used',
      render: (k) => (k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'),
    },
    {
      key: 'expires',
      header: 'Expires',
      render: (k) => (k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : 'Never'),
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
          <Link to={`/orgs/${orgId}`} className="hover:text-brand-700">
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
          <Field label="Expires at (optional)" htmlFor="ak-exp">
            <Input id="ak-exp" type="date" {...form.register('expiresAt')} />
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
          <div className="space-y-3">
            <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              Store this in a secret manager. We cannot recover it.
            </div>
            <pre className="overflow-auto rounded-md border border-slate-200 bg-slate-50 p-3 text-xs">
              {createdKey.plaintext ?? `${createdKey.prefix}...`}
            </pre>
            <Button
              variant="outline"
              onClick={() => {
                if (createdKey.plaintext) {
                  navigator.clipboard.writeText(createdKey.plaintext).then(
                    () => toast.success('Copied to clipboard'),
                    () => toast.error('Copy failed'),
                  );
                }
              }}
            >
              Copy
            </Button>
          </div>
        )}
      </Dialog>
    </div>
  );
}
