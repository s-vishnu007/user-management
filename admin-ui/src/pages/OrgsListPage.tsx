import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { motion } from 'framer-motion';
import { orgs, apiErrorMessage } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Dialog, Field, Input, StatusBadge } from '@/components/ui';
import { DataTable, type Column } from '@/components/DataTable';
import { PermissionGate } from '@/components/PermissionGate';
import { useToast } from '@/lib/toast';
import { fadeRise, staggerContainer } from '@/lib/motion';
import type { Organization } from '@/lib/types';

const schema = z.object({
  name: z.string().min(2, 'Name is required'),
  slug: z
    .string()
    .min(2, 'Slug is required')
    .regex(/^[a-z0-9-]+$/, 'Lowercase letters, digits, hyphens only'),
});
type FormValues = z.infer<typeof schema>;

export function OrgsListPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const [openCreate, setOpenCreate] = useState(false);
  const [filter, setFilter] = useState('');

  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', slug: '' },
  });

  const createMut = useMutation({
    mutationFn: (v: FormValues) => orgs.create(v),
    onSuccess: (org) => {
      qc.invalidateQueries({ queryKey: ['orgs'] });
      setOpenCreate(false);
      form.reset();
      toast.success(`Organization "${org.name}" created`);
      navigate(`/orgs/${org.id}`);
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const filtered = (orgsQ.data ?? []).filter((o) =>
    !filter
      ? true
      : `${o.name} ${o.slug}`.toLowerCase().includes(filter.toLowerCase()),
  );

  const columns: Column<Organization>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (o) => <span className="font-medium text-ink">{o.name}</span>,
    },
    {
      key: 'slug',
      header: 'Slug',
      render: (o) => (
        <code className="rounded bg-slate-100/70 px-1.5 py-0.5 font-mono text-xs text-ink-soft">
          {o.slug}
        </code>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (o) => <StatusBadge status={o.status ?? 'ACTIVE'} />,
    },
    {
      key: 'members',
      header: 'Members',
      render: (o) => o.memberCount ?? '—',
    },
    {
      key: 'createdAt',
      header: 'Created',
      render: (o) => (o.createdAt ? new Date(o.createdAt).toLocaleDateString() : '—'),
    },
  ];

  return (
    <div>
      {/* Page composes in on navigation; anchored to this always-mounted root so a
          background TanStack refetch does NOT replay the cascade. */}
      <motion.div variants={staggerContainer} initial="hidden" animate="show">
        <motion.div variants={fadeRise}>
          <PageHeader
            title="Organizations"
            description="Each customer is an organization. Subscriptions and licenses live under one."
            actions={
              // Aligned to the authority the create endpoint actually enforces (org.write), not the
              // non-enforced org.create code (finding P3: UI gate codes diverged from authorities).
              <PermissionGate permission="org.write">
                <Button onClick={() => setOpenCreate(true)}>New organization</Button>
              </PermissionGate>
            }
          />
        </motion.div>

        <motion.div variants={fadeRise}>
          <DataTable
            rows={filtered}
            columns={columns}
            rowKey={(o) => o.id}
            loading={orgsQ.isLoading}
            empty={orgsQ.isError ? apiErrorMessage(orgsQ.error) : 'No organizations yet.'}
            onRowClick={(o) => navigate(`/orgs/${o.id}`)}
            toolbar={
              <div className="flex w-full items-center justify-between gap-3">
                <Input
                  placeholder="Search by name or slug"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  aria-label="Search organizations by name or slug"
                  className="max-w-sm"
                />
                <span className="hidden shrink-0 text-sm tabular-nums text-ink-muted sm:inline">
                  {filtered.length} {filtered.length === 1 ? 'organization' : 'organizations'}
                </span>
              </div>
            }
          />
        </motion.div>
      </motion.div>

      <Dialog
        open={openCreate}
        onClose={() => setOpenCreate(false)}
        title="Create organization"
        description="Slug is used in URLs and API identifiers."
        footer={
          <>
            <Button variant="outline" onClick={() => setOpenCreate(false)}>
              Cancel
            </Button>
            <Button
              type="submit"
              form="create-org-form"
              loading={createMut.isPending}
            >
              Create
            </Button>
          </>
        }
      >
        <form
          id="create-org-form"
          onSubmit={form.handleSubmit((v) => createMut.mutate(v))}
          className="space-y-4"
        >
          <Field label="Name" htmlFor="org-name" required error={form.formState.errors.name?.message}>
            <Input id="org-name" {...form.register('name')} invalid={!!form.formState.errors.name} />
          </Field>
          <Field label="Slug" htmlFor="org-slug" required error={form.formState.errors.slug?.message}>
            <Input id="org-slug" {...form.register('slug')} invalid={!!form.formState.errors.slug} />
          </Field>
        </form>
      </Dialog>
    </div>
  );
}
