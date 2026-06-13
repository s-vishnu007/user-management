import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
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
  key: string;
  type: FeatureType;
  value: string | number | boolean;
}

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
    setFeatures((f) => [...f, { key: '', type: 'boolean', value: true }]);
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
    return <div className="text-rose-600">{apiErrorMessage(planQ.error)}</div>;

  return (
    <div>
      <PageHeader
        title={planQ.data?.name ?? 'Plan'}
        description={`Code: ${planQ.data?.code ?? ''}`}
        breadcrumb={
          <Link to="/plans" className="hover:text-brand-700">
            Plans
          </Link>
        }
      />

      <div className="space-y-6">
        <Card>
          <CardHeader title="Details" />
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
              <div className="text-sm text-rose-600">{apiErrorMessage(permsQ.error)}</div>
            ) : grouped.length === 0 ? (
              <div className="text-sm text-slate-500">No permissions defined.</div>
            ) : (
              <div className="space-y-5">
                {grouped.map(([cat, items]) => (
                  <div key={cat}>
                    <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
                      {cat}
                    </div>
                    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
                      {items.map((p) => {
                        const checked = selectedPerms.has(p.code);
                        return (
                          <label
                            key={p.code}
                            className={
                              'flex cursor-pointer items-start gap-2 rounded-md border px-3 py-2 text-sm transition-colors ' +
                              (checked
                                ? 'border-brand-300 bg-brand-50'
                                : 'border-slate-200 hover:bg-slate-50')
                            }
                          >
                            <input
                              type="checkbox"
                              className="mt-0.5 accent-brand-600"
                              checked={checked}
                              onChange={() => togglePerm(p.code)}
                            />
                            <div>
                              <code className="block text-xs font-medium">{p.code}</code>
                              {p.description ? (
                                <span className="text-xs text-slate-500">{p.description}</span>
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
            <div className="mt-4 text-xs text-slate-500">
              {selectedPerms.size} selected
              {selectedPerms.size > 0 ? (
                <div className="mt-2 flex flex-wrap gap-1">
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
              <div className="rounded-md border border-dashed border-slate-200 p-4 text-sm text-slate-500">
                No features defined. Click "Add feature" to start.
              </div>
            ) : (
              <div className="space-y-2">
                {features.map((f, i) => (
                  <div
                    key={i}
                    className="grid grid-cols-1 items-end gap-2 rounded-md border border-slate-200 p-3 md:grid-cols-12"
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
                  </div>
                ))}
              </div>
            )}
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
