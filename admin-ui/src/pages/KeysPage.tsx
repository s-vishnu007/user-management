import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { apiErrorMessage, keys } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Dialog, StatusBadge } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import type { SigningKey } from '@/lib/types';

export function KeysPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [confirmOpen, setConfirmOpen] = useState(false);

  const keysQ = useQuery({ queryKey: ['keys'], queryFn: keys.list });

  const rotateMut = useMutation({
    mutationFn: () => keys.rotate(),
    onSuccess: (k) => {
      qc.invalidateQueries({ queryKey: ['keys'] });
      setConfirmOpen(false);
      toast.success(`New active key: ${k.kid}`);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const columns: Column<SigningKey>[] = [
    {
      key: 'kid',
      header: 'Key ID',
      render: (k) => (
        <code className="inline-flex max-w-full items-center truncate rounded-md bg-slate-100/80 px-2 py-1 font-mono text-xs text-ink ring-1 ring-inset ring-slate-900/5">
          {k.kid}
        </code>
      ),
    },
    {
      key: 'alg',
      header: 'Algorithm',
      render: (k) => <span className="font-mono text-xs text-ink-soft">{k.algorithm}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (k) => <StatusBadge status={k.status} />,
    },
    {
      key: 'created',
      header: 'Created',
      render: (k) => (
        <span className="tabular-nums text-ink-soft">{new Date(k.createdAt).toLocaleString()}</span>
      ),
    },
    {
      key: 'activated',
      header: 'Activated',
      render: (k) =>
        k.activatedAt ? (
          <span className="tabular-nums text-ink-soft">
            {new Date(k.activatedAt).toLocaleString()}
          </span>
        ) : (
          <span className="text-ink-faint" aria-hidden="true">
            —
          </span>
        ),
    },
    {
      key: 'retired',
      header: 'Retired',
      render: (k) =>
        k.retiredAt ? (
          <span className="tabular-nums text-ink-soft">
            {new Date(k.retiredAt).toLocaleString()}
          </span>
        ) : (
          <span className="text-ink-faint" aria-hidden="true">
            —
          </span>
        ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Signing keys"
        description="Ed25519 keypairs used to sign license JWTs. Old keys remain published in JWKS until retired."
        actions={
          <PermissionGate permission="key.rotate">
            <Button onClick={() => setConfirmOpen(true)}>Rotate signing key</Button>
          </PermissionGate>
        }
      />

      <DataTable
        rows={keysQ.data}
        columns={columns}
        rowKey={(k) => k.kid}
        loading={keysQ.isLoading}
        empty={keysQ.isError ? apiErrorMessage(keysQ.error) : 'No signing keys.'}
      />

      <Dialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        title="Rotate signing key?"
        description="A new Ed25519 keypair will be generated and marked active. Existing licenses keep verifying against the previous key until it is retired."
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => rotateMut.mutate()} loading={rotateMut.isPending}>
              Rotate
            </Button>
          </>
        }
      >
        <div className="flex items-start gap-3 rounded-xl border border-warn-200 bg-warn-50/70 p-3.5 text-sm text-warn-700">
          <span
            className="mt-0.5 flex h-8 w-8 flex-none items-center justify-center rounded-full bg-warn-100 text-warn-600 ring-1 ring-inset ring-warn-200"
            aria-hidden="true"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M12 9v4" />
              <path d="M12 17h.01" />
              <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" />
            </svg>
          </span>
          <div>
            <p className="font-medium text-warn-700">This action takes effect immediately.</p>
            <p className="mt-1 text-warn-700/90">
              All newly issued licenses will be signed by the new key. Retire the previous key only
              after dependent apps have refreshed their JWKS.
            </p>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
