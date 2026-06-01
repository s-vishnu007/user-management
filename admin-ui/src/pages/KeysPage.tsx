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
    mutationFn: keys.rotate,
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
      render: (k) => <code className="text-xs">{k.kid}</code>,
    },
    { key: 'alg', header: 'Algorithm', render: (k) => k.algorithm },
    {
      key: 'status',
      header: 'Status',
      render: (k) => <StatusBadge status={k.status} />,
    },
    {
      key: 'created',
      header: 'Created',
      render: (k) => new Date(k.createdAt).toLocaleString(),
    },
    {
      key: 'activated',
      header: 'Activated',
      render: (k) => (k.activatedAt ? new Date(k.activatedAt).toLocaleString() : '—'),
    },
    {
      key: 'retired',
      header: 'Retired',
      render: (k) => (k.retiredAt ? new Date(k.retiredAt).toLocaleString() : '—'),
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
      />
    </div>
  );
}
