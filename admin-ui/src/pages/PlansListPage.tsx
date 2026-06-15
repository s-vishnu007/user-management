import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { motion } from 'framer-motion';
import { apiErrorMessage, plans } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Dialog, Field, Input, StatusBadge } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { fadeRise } from '@/lib/motion';
import type { Plan } from '@/lib/types';

const schema = z.object({
  name: z.string().min(2),
  code: z
    .string()
    .min(2)
    .regex(/^[a-z0-9-_]+$/, 'Lowercase letters, digits, underscore, hyphen only'),
  description: z.string().optional(),
});
type Values = z.infer<typeof schema>;

export function PlansListPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const [open, setOpen] = useState(false);

  const plansQ = useQuery({ queryKey: ['plans'], queryFn: plans.list });

  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { name: '', code: '' } });

  const createMut = useMutation({
    mutationFn: (v: Values) => plans.create({ ...v, permissions: [], features: {} }),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ['plans'] });
      setOpen(false);
      form.reset();
      toast.success(`Plan "${p.name}" created`);
      navigate(`/plans/${p.id}/edit`);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const columns: Column<Plan>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (p) => <span className="font-medium text-ink">{p.name}</span>,
    },
    {
      key: 'code',
      header: 'Code',
      render: (p) => (
        <code className="rounded bg-slate-100/70 px-1.5 py-0.5 font-mono text-xs text-ink-soft">
          {p.code}
        </code>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusBadge status={p.active === false ? 'RETIRED' : 'ACTIVE'} />,
    },
    {
      key: 'permissions',
      header: 'Permissions',
      render: (p) => <span className="tabular-nums text-ink-soft">{p.permissions?.length ?? 0}</span>,
    },
    {
      key: 'features',
      header: 'Features',
      render: (p) => (
        <span className="tabular-nums text-ink-soft">{Object.keys(p.features ?? {}).length}</span>
      ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (p) => (
        <Button variant="ghost" size="sm" onClick={() => navigate(`/plans/${p.id}/edit`)}>
          Edit
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Plans"
        description="The catalog of subscription tiers. Each plan grants a permission set and feature flags to issued licenses."
        actions={
          // Plan create enforces plan.write on the backend; gate on the real authority (P3).
          <PermissionGate permission="plan.write">
            <Button onClick={() => setOpen(true)}>New plan</Button>
          </PermissionGate>
        }
      />

      <motion.div variants={fadeRise} initial="hidden" animate="show">
        <DataTable
          rows={plansQ.data}
          columns={columns}
          rowKey={(p) => p.id}
          loading={plansQ.isLoading}
          empty={plansQ.isError ? apiErrorMessage(plansQ.error) : 'No plans defined yet.'}
          onRowClick={(p) => navigate(`/plans/${p.id}/edit`)}
        />
      </motion.div>

      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Create plan"
        footer={
          <>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button form="create-plan-form" type="submit" loading={createMut.isPending}>
              Create
            </Button>
          </>
        }
      >
        <form
          id="create-plan-form"
          onSubmit={form.handleSubmit((v) => createMut.mutate(v))}
          className="space-y-4"
        >
          <Field label="Name" htmlFor="p-name" required error={form.formState.errors.name?.message}>
            <Input id="p-name" {...form.register('name')} invalid={!!form.formState.errors.name} />
          </Field>
          <Field label="Code" htmlFor="p-code" required error={form.formState.errors.code?.message}>
            <Input id="p-code" {...form.register('code')} invalid={!!form.formState.errors.code} />
          </Field>
          <Field label="Description" htmlFor="p-desc">
            <Input id="p-desc" {...form.register('description')} />
          </Field>
        </form>
      </Dialog>
    </div>
  );
}
