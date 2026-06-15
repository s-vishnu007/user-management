import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AnimatePresence, motion } from 'framer-motion';
import { apiErrorMessage, plans, rbac } from '@/lib/api';
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
} from '@/components/ui';
import { useToast } from '@/lib/toast';
import { fadeRise, staggerContainer, SPRING } from '@/lib/motion';
import type { PlanFeature } from '@/lib/types';

const metaSchema = z.object({
  name: z.string().min(2),
  code: z.string().min(2),
  description: z.string().optional(),
  defaultTtlDays: z.coerce.number().int().positive().optional(),
});
type MetaValues = z.infer<typeof metaSchema>;

type FeatureType = 'boolean' | 'number' | 'string';

function inferType(v: unknown): FeatureType {
  if (typeof v === 'boolean') return 'boolean';
  if (typeof v === 'number') return 'number';
  return 'string';
}

interface EditableFeature {
  // Stable per-row identity for React keys / AnimatePresence. Presentation-only:
  // it is never part of the saveFeatures payload (which is rebuilt keyed by f.key).
  id: string;
  key: string;
  type: FeatureType;
  value: string | number | boolean;
}

// crypto.randomUUID is available in all evergreen browsers; fall back just in case.
const newFeatureId = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `f-${Math.random().toString(36).slice(2)}-${Date.now()}`;

export function PlanEditPage() {
  const { planId = '' } = useParams<{ planId: string }>();
  const qc = useQueryClient();
  const toast = useToast();

  const planQ = useQuery({ queryKey: ['plan', planId], queryFn: () => plans.get(planId), enabled: !!planId });
  const permsQ = useQuery({ queryKey: ['rbac', 'permissions'], queryFn: rbac.permissions });

  const form = useForm<MetaValues>({ resolver: zodResolver(metaSchema) });
  const [selectedPerms, setSelectedPerms] = useState<Set<string>>(new Set());
  const [features, setFeatures] = useState<EditableFeature[]>([]);

  useEffect(() => {
    if (planQ.data) {
      form.reset({
        name: planQ.data.name,
        code: planQ.data.code,
        description: planQ.data.description ?? '',
        defaultTtlDays: planQ.data.defaultTtlDays ?? undefined,
      });
      setSelectedPerms(new Set(planQ.data.permissions ?? []));
      // features is a JSON object (Map<String,Object>) keyed by feature key, not an array.
      setFeatures(
        Object.entries(planQ.data.features ?? {}).map(([key, value]) => ({
          id: newFeatureId(),
          key,
          type: inferType(value),
          value: value as string | number | boolean,
        })),
      );
    }
  }, [planQ.data, form]);

  const grouped = useMemo(() => {
    const groups = new Map<string, { code: string; description?: string }[]>();
    for (const p of permsQ.data ?? []) {
      const cat = p.category ?? p.code.split('.')[0] ?? 'other';
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat)!.push(p);
    }
    return Array.from(groups.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [permsQ.data]);

  const togglePerm = (code: string) => {
    setSelectedPerms((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  };

  const addFeature = () => {
    setFeatures((f) => [...f, { id: newFeatureId(), key: '', type: 'boolean', value: true }]);
  };

  const updateFeature = (i: number, patch: Partial<EditableFeature>) => {
    setFeatures((f) => f.map((row, idx) => (idx === i ? { ...row, ...patch } : row)));
  };

  const removeFeature = (i: number) => {
    setFeatures((f) => f.filter((_, idx) => idx !== i));
  };

  const saveMeta = useMutation({
    // The PATCH endpoint (UpdatePlanRequest) accepts name/description/tier/defaultTtlDays/active —
    // NOT code (the plan code is immutable post-creation), so we only send the editable fields.
    mutationFn: (v: MetaValues) =>
      plans.update(planId, {
        name: v.name,
        description: v.description,
        defaultTtlDays: v.defaultTtlDays,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['plan', planId] });
      toast.success('Plan saved');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const savePerms = useMutation({
    mutationFn: () => plans.setPermissions(planId, Array.from(selectedPerms).sort()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['plan', planId] });
      toast.success('Permissions updated');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const saveFeatures = useMutation({
    // The endpoint expects a JSON object (Map<String,Object>) keyed by feature key.
    mutationFn: () => {
      const obj: Record<string, PlanFeature['value']> = {};
      for (const f of features) {
        const key = f.key.trim();
        if (key === '') continue;
        let value: PlanFeature['value'] = f.value;
        if (f.type === 'boolean') value = String(f.value) === 'true' || f.value === true;
        else if (f.type === 'number') value = Number(f.value);
        obj[key] = value;
      }
      return plans.setFeatures(planId, obj);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['plan', planId] });
      toast.success('Features updated');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  if (planQ.isLoading) return <PageLoader />;
  if (planQ.isError)
    return (
      <div className="rounded-xl border border-danger-200 bg-danger-50/70 px-4 py-3 text-sm text-danger-700">
        {apiErrorMessage(planQ.error)}
      </div>
    );

  return (
    <div>
      <PageHeader
        title={planQ.data?.name ?? 'Plan'}
        description={`Code: ${planQ.data?.code ?? ''}`}
        breadcrumb={
          <Link to="/plans" className="font-medium text-ink-muted transition-colors hover:text-indigo-700">
            Plans
          </Link>
        }
      />

      <motion.div
        className="space-y-6"
        variants={staggerContainer}
        initial="hidden"
        animate="show"
      >
        <motion.div variants={fadeRise}>
          <Card>
            <CardHeader title="Details" description="Identity and issuance defaults for this plan." />
            <CardBody>
              <form
                onSubmit={form.handleSubmit((v) => saveMeta.mutate(v))}
                className="grid grid-cols-1 gap-4 md:grid-cols-2"
              >
                <Field label="Name" htmlFor="name" required error={form.formState.errors.name?.message}>
                  <Input id="name" {...form.register('name')} />
                </Field>
                <Field label="Code" htmlFor="code" required error={form.formState.errors.code?.message}>
                  <Input id="code" {...form.register('code')} />
                </Field>
                <Field label="Description" htmlFor="desc">
                  <Input id="desc" {...form.register('description')} />
                </Field>
                <Field
                  label="Default license TTL (days)"
                  htmlFor="ttl"
                  hint="Used when issuing new licenses"
                  error={form.formState.errors.defaultTtlDays?.message}
                >
                  <Input id="ttl" type="number" {...form.register('defaultTtlDays')} />
                </Field>
                <div className="md:col-span-2">
                  <Button type="submit" loading={saveMeta.isPending}>
                    Save details
                  </Button>
                </div>
              </form>
            </CardBody>
          </Card>
        </motion.div>

        <motion.div variants={fadeRise}>
          <Card>
            <CardHeader
              title="Entitlement permissions"
              description="Permissions baked into licenses issued from this plan."
              actions={
                <Button onClick={() => savePerms.mutate()} loading={savePerms.isPending}>
                  Save permissions
                </Button>
              }
            />
            <CardBody>
              {permsQ.isLoading ? (
                <PageLoader />
              ) : permsQ.isError ? (
                <div className="rounded-xl border border-danger-200 bg-danger-50/70 px-4 py-3 text-sm text-danger-700">
                  {apiErrorMessage(permsQ.error)}
                </div>
              ) : grouped.length === 0 ? (
                <div className="text-sm text-ink-muted">No permissions defined.</div>
              ) : (
                <div className="space-y-5">
                  {grouped.map(([cat, items]) => (
                    <div key={cat}>
                      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                        {cat}
                      </div>
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
                        {items.map((p) => {
                          const checked = selectedPerms.has(p.code);
                          return (
                            <label
                              key={p.code}
                              className={
                                'group flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 text-sm shadow-glass-sm transition-all duration-fast hover:-translate-y-px ' +
                                (checked
                                  ? 'border-indigo-300 bg-gradient-to-br from-indigo-50 to-violet-50 ring-1 ring-indigo-500/10'
                                  : 'border-slate-200/80 bg-white/60 hover:border-indigo-200 hover:bg-white/90')
                              }
                            >
                              <input
                                type="checkbox"
                                className="mt-0.5 h-4 w-4 accent-indigo-600"
                                checked={checked}
                                onChange={() => togglePerm(p.code)}
                              />
                              <div>
                                <code
                                  className={
                                    'block font-mono text-xs font-semibold ' +
                                    (checked ? 'text-indigo-700' : 'text-ink-soft')
                                  }
                                >
                                  {p.code}
                                </code>
                                {p.description ? (
                                  <span className="text-xs text-ink-muted">{p.description}</span>
                                ) : null}
                              </div>
                            </label>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              )}
              <div className="mt-4 text-xs text-ink-muted">
                <span className="tabular-nums">{selectedPerms.size}</span> selected
                {selectedPerms.size > 0 ? (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {Array.from(selectedPerms).slice(0, 12).map((c) => (
                      <Badge key={c} tone="info">
                        {c}
                      </Badge>
                    ))}
                    {selectedPerms.size > 12 ? (
                      <Badge tone="neutral">+{selectedPerms.size - 12} more</Badge>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </CardBody>
          </Card>
        </motion.div>

        <motion.div variants={fadeRise}>
          <Card>
            <CardHeader
              title="Features and quotas"
              description="Feature flags and numeric quotas embedded in the license JWT."
              actions={
                <>
                  <Button variant="outline" onClick={addFeature}>
                    Add feature
                  </Button>
                  <Button onClick={() => saveFeatures.mutate()} loading={saveFeatures.isPending}>
                    Save features
                  </Button>
                </>
              }
            />
            <CardBody>
              {features.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-slate-300/80 bg-white/40 px-6 py-10 text-center">
                  <span className="flex h-11 w-11 items-center justify-center rounded-full bg-aurora-chip text-indigo-600">
                    <svg
                      className="h-5 w-5"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      <path d="M12 5v14M5 12h14" />
                    </svg>
                  </span>
                  <p className="text-sm text-ink-muted">
                    No features defined. Click "Add feature" to start.
                  </p>
                </div>
              ) : (
                <div className="space-y-2.5">
                  <AnimatePresence initial={false}>
                    {features.map((f, i) => (
                      <motion.div
                        key={f.id}
                        layout
                        initial={{ opacity: 0, y: -6 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: -6, transition: { duration: 0.12 } }}
                        transition={SPRING.gentle}
                        className="grid grid-cols-1 items-end gap-2 rounded-xl border border-slate-200/80 bg-white/60 p-3 shadow-glass-sm transition-colors hover:border-indigo-200 hover:bg-white/80 md:grid-cols-12"
                      >
                        <div className="md:col-span-4">
                          <Field label="Key" htmlFor={`f-key-${i}`}>
                            <Input
                              id={`f-key-${i}`}
                              value={f.key}
                              onChange={(e) => updateFeature(i, { key: e.target.value })}
                              placeholder="e.g. max_users"
                            />
                          </Field>
                        </div>
                        <div className="md:col-span-3">
                          <Field label="Type" htmlFor={`f-type-${i}`}>
                            <Select
                              id={`f-type-${i}`}
                              value={f.type}
                              onChange={(e) => {
                                const t = e.target.value as FeatureType;
                                const v = t === 'boolean' ? true : t === 'number' ? 0 : '';
                                updateFeature(i, { type: t, value: v });
                              }}
                            >
                              <option value="boolean">boolean</option>
                              <option value="number">number</option>
                              <option value="string">string</option>
                            </Select>
                          </Field>
                        </div>
                        <div className="md:col-span-4">
                          <Field label="Value" htmlFor={`f-val-${i}`}>
                            {f.type === 'boolean' ? (
                              <Select
                                id={`f-val-${i}`}
                                value={String(f.value)}
                                onChange={(e) => updateFeature(i, { value: e.target.value === 'true' })}
                              >
                                <option value="true">true</option>
                                <option value="false">false</option>
                              </Select>
                            ) : f.type === 'number' ? (
                              <Input
                                id={`f-val-${i}`}
                                type="number"
                                value={String(f.value)}
                                onChange={(e) => updateFeature(i, { value: Number(e.target.value) })}
                              />
                            ) : (
                              <Input
                                id={`f-val-${i}`}
                                value={String(f.value)}
                                onChange={(e) => updateFeature(i, { value: e.target.value })}
                              />
                            )}
                          </Field>
                        </div>
                        <div className="md:col-span-1 md:text-right">
                          <Button variant="ghost" size="sm" onClick={() => removeFeature(i)}>
                            Remove
                          </Button>
                        </div>
                      </motion.div>
                    ))}
                  </AnimatePresence>
                </div>
              )}
            </CardBody>
          </Card>
        </motion.div>
      </motion.div>
    </div>
  );
}
