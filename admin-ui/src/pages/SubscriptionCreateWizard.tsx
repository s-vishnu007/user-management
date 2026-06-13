import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { apiErrorMessage, licenses, newIdempotencyKey, orgs, plans, subscriptions } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Card, CardBody, CardHeader, Field, Input, PageLoader, Select, StatusBadge } from '@/components/ui';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import type { SubscriptionOverride } from '@/lib/types';

interface WizardState {
  orgId: string;
  planId: string;
  startsAt: string;
  endsAt: string;
  seats: number;
  overrides: SubscriptionOverride[];
}

const todayIso = () => new Date().toISOString().slice(0, 10);
const yearFromNow = () => {
  const d = new Date();
  d.setFullYear(d.getFullYear() + 1);
  return d.toISOString().slice(0, 10);
};

export function SubscriptionCreateWizard() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const toast = useToast();
  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });
  const plansQ = useQuery({ queryKey: ['plans'], queryFn: plans.list });

  const [step, setStep] = useState(0);
  const steps = ['Organization', 'Plan', 'Dates and seats', 'Overrides', 'Confirm'];

  const [state, setState] = useState<WizardState>({
    orgId: searchParams.get('orgId') ?? '',
    planId: '',
    startsAt: todayIso(),
    endsAt: yearFromNow(),
    seats: 25,
    overrides: [],
  });

  useEffect(() => {
    const o = searchParams.get('orgId');
    if (o) setState((s) => ({ ...s, orgId: o }));
  }, [searchParams]);

  const selectedOrg = useMemo(
    () => (orgsQ.data ?? []).find((o) => o.id === state.orgId),
    [orgsQ.data, state.orgId],
  );
  const selectedPlan = useMemo(
    () => (plansQ.data ?? []).find((p) => p.id === state.planId),
    [plansQ.data, state.planId],
  );

  const canNext =
    (step === 0 && !!state.orgId) ||
    (step === 1 && !!state.planId) ||
    (step === 2 && !!state.startsAt && !!state.endsAt && state.seats > 0) ||
    step === 3 ||
    step === 4;

  // `submitting` stays true for the WHOLE create(+issue+download) sequence so the action buttons
  // remain disabled until everything settles — a re-enable mid-flight is what lets a double-click
  // create a duplicate subscription (finding P2). We also send a stable per-submission
  // Idempotency-Key so a retried/duplicate POST is collapsed server-side.
  const [submitting, setSubmitting] = useState(false);
  // Hold one idempotency key per submission attempt; regenerated only when a submission begins.
  const idempotencyKey = useRef<string>(newIdempotencyKey());

  const issueAndDownload = async (subId: string) => {
    const lic = await licenses.issue(subId, undefined, idempotencyKey.current + ':license');
    try {
      const { blob, filename } = await licenses.download(lic.jti);
      triggerDownload(blob, filename);
      toast.success('License issued and downloaded');
    } catch (e) {
      toast.error(`License issued but download failed: ${apiErrorMessage(e)}`);
    }
  };

  const onCreate = async (issue: boolean) => {
    if (submitting) return;
    setSubmitting(true);
    try {
      const sub = await subscriptions.create(
        state.orgId,
        {
          planId: state.planId,
          startsAt: new Date(state.startsAt).toISOString(),
          endsAt: new Date(state.endsAt).toISOString(),
          seats: state.seats,
          overrides: state.overrides.length ? state.overrides : undefined,
        },
        idempotencyKey.current,
      );
      toast.success('Subscription created');
      if (issue) await issueAndDownload(sub.id);
      navigate(`/subscriptions/${sub.id}`);
    } catch (e) {
      toast.error(apiErrorMessage(e));
      // Failed: allow another attempt with a fresh key (the failed POST left no committed row).
      idempotencyKey.current = newIdempotencyKey();
      setSubmitting(false);
    }
  };

  const addOverride = () => {
    setState((s) => ({
      ...s,
      overrides: [...s.overrides, { type: 'PERMISSION_ADD', key: '', value: undefined }],
    }));
  };
  const updateOverride = (i: number, patch: Partial<SubscriptionOverride>) => {
    setState((s) => ({
      ...s,
      overrides: s.overrides.map((o, idx) => (idx === i ? { ...o, ...patch } : o)),
    }));
  };
  const removeOverride = (i: number) => {
    setState((s) => ({ ...s, overrides: s.overrides.filter((_, idx) => idx !== i) }));
  };

  return (
    <div>
      <PageHeader
        title="New subscription"
        description="Provision a customer's plan, set the term, and optionally issue the first license."
        breadcrumb={
          <>
            <Link to="/orgs" className="hover:text-brand-700">
              Organizations
            </Link>{' '}
            /
          </>
        }
      />

      <div className="mb-6">
        <ol className="flex flex-wrap gap-2 text-xs">
          {steps.map((label, i) => (
            <li key={label} className="flex items-center gap-2">
              <span
                className={
                  'grid h-6 w-6 place-items-center rounded-full text-[11px] font-semibold ' +
                  (i === step
                    ? 'bg-brand-600 text-white'
                    : i < step
                      ? 'bg-emerald-500 text-white'
                      : 'bg-slate-200 text-slate-600')
                }
              >
                {i + 1}
              </span>
              <span className={i === step ? 'font-medium text-slate-900' : 'text-slate-500'}>
                {label}
              </span>
              {i < steps.length - 1 && <span className="text-slate-300">/</span>}
            </li>
          ))}
        </ol>
      </div>

      <Card>
        <CardBody>
          {step === 0 && (
            <div className="max-w-md">
              <h3 className="mb-3 text-sm font-medium text-slate-900">Choose an organization</h3>
              {orgsQ.isLoading ? (
                <PageLoader />
              ) : (
                <Field label="Organization" htmlFor="org">
                  <Select
                    id="org"
                    value={state.orgId}
                    onChange={(e) => setState((s) => ({ ...s, orgId: e.target.value }))}
                  >
                    <option value="">Select...</option>
                    {(orgsQ.data ?? []).map((o) => (
                      <option key={o.id} value={o.id}>
                        {o.name} ({o.slug})
                      </option>
                    ))}
                  </Select>
                </Field>
              )}
            </div>
          )}

          {step === 1 && (
            <div className="max-w-2xl">
              <h3 className="mb-3 text-sm font-medium text-slate-900">Choose a plan</h3>
              {plansQ.isLoading ? (
                <PageLoader />
              ) : (
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {(plansQ.data ?? []).map((p) => {
                    const selected = state.planId === p.id;
                    return (
                      <button
                        key={p.id}
                        type="button"
                        onClick={() => setState((s) => ({ ...s, planId: p.id }))}
                        className={
                          'rounded-md border p-4 text-left transition-colors ' +
                          (selected
                            ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-300'
                            : 'border-slate-200 hover:bg-slate-50')
                        }
                      >
                        <div className="flex items-center justify-between">
                          <div className="font-medium text-slate-900">{p.name}</div>
                          <StatusBadge status={p.active === false ? 'RETIRED' : 'ACTIVE'} />
                        </div>
                        <div className="mt-1 text-xs text-slate-500">
                          <code>{p.code}</code>
                        </div>
                        {p.description ? (
                          <p className="mt-2 text-sm text-slate-600">{p.description}</p>
                        ) : null}
                        <div className="mt-3 text-xs text-slate-500">
                          {p.permissions?.length ?? 0} permissions ·{' '}
                          {Object.keys(p.features ?? {}).length} features
                        </div>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {step === 2 && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label="Starts at" htmlFor="starts" required>
                <Input
                  id="starts"
                  type="date"
                  value={state.startsAt}
                  onChange={(e) => setState((s) => ({ ...s, startsAt: e.target.value }))}
                />
              </Field>
              <Field label="Ends at" htmlFor="ends" required>
                <Input
                  id="ends"
                  type="date"
                  value={state.endsAt}
                  onChange={(e) => setState((s) => ({ ...s, endsAt: e.target.value }))}
                />
              </Field>
              <Field label="Seats" htmlFor="seats" required>
                <Input
                  id="seats"
                  type="number"
                  min={1}
                  value={state.seats}
                  onChange={(e) =>
                    setState((s) => ({ ...s, seats: Math.max(1, Number(e.target.value)) }))
                  }
                />
              </Field>
            </div>
          )}

          {step === 3 && (
            <div>
              <div className="mb-3 flex items-center justify-between">
                <h3 className="text-sm font-medium text-slate-900">Overrides (optional)</h3>
                <Button size="sm" variant="outline" onClick={addOverride}>
                  Add override
                </Button>
              </div>
              {state.overrides.length === 0 ? (
                <div className="rounded-md border border-dashed border-slate-200 p-4 text-sm text-slate-500">
                  Plan entitlements will be used as-is. Add overrides for per-customer terms.
                </div>
              ) : (
                <div className="space-y-2">
                  {state.overrides.map((o, i) => (
                    <div
                      key={i}
                      className="grid grid-cols-1 items-end gap-2 rounded-md border border-slate-200 p-3 md:grid-cols-12"
                    >
                      <div className="md:col-span-3">
                        <Field label="Type" htmlFor={`o-type-${i}`}>
                          <Select
                            id={`o-type-${i}`}
                            value={o.type}
                            onChange={(e) =>
                              updateOverride(i, { type: e.target.value as SubscriptionOverride['type'] })
                            }
                          >
                            <option value="PERMISSION_ADD">PERMISSION_ADD</option>
                            <option value="PERMISSION_REMOVE">PERMISSION_REMOVE</option>
                            <option value="FEATURE_SET">FEATURE_SET</option>
                          </Select>
                        </Field>
                      </div>
                      <div className="md:col-span-4">
                        <Field label="Key" htmlFor={`o-key-${i}`}>
                          <Input
                            id={`o-key-${i}`}
                            value={o.key}
                            onChange={(e) => updateOverride(i, { key: e.target.value })}
                            placeholder={
                              o.type === 'FEATURE_SET' ? 'feature key' : 'permission code'
                            }
                          />
                        </Field>
                      </div>
                      <div className="md:col-span-4">
                        <Field label="Value" htmlFor={`o-val-${i}`}>
                          <Input
                            id={`o-val-${i}`}
                            disabled={o.type !== 'FEATURE_SET'}
                            value={o.value === undefined ? '' : String(o.value)}
                            onChange={(e) => updateOverride(i, { value: e.target.value })}
                          />
                        </Field>
                      </div>
                      <div className="md:col-span-1 md:text-right">
                        <Button variant="ghost" size="sm" onClick={() => removeOverride(i)}>
                          Remove
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {step === 4 && (
            <div>
              <h3 className="mb-3 text-sm font-medium text-slate-900">Confirm</h3>
              <Card>
                <CardHeader title="Summary" />
                <CardBody>
                  <dl className="grid grid-cols-1 gap-y-3 text-sm sm:grid-cols-2">
                    <div>
                      <dt className="text-xs text-slate-500">Organization</dt>
                      <dd className="font-medium">{selectedOrg?.name ?? state.orgId}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-slate-500">Plan</dt>
                      <dd className="font-medium">{selectedPlan?.name ?? state.planId}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-slate-500">Term</dt>
                      <dd>
                        {state.startsAt} → {state.endsAt}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs text-slate-500">Seats</dt>
                      <dd>{state.seats}</dd>
                    </div>
                    <div className="sm:col-span-2">
                      <dt className="text-xs text-slate-500">Overrides</dt>
                      <dd>
                        {state.overrides.length === 0 ? (
                          <span className="text-slate-500">None</span>
                        ) : (
                          <ul className="list-inside list-disc">
                            {state.overrides.map((o, i) => (
                              <li key={i}>
                                <code className="text-xs">{o.type}</code> · {o.key}
                                {o.value !== undefined ? ` = ${String(o.value)}` : ''}
                              </li>
                            ))}
                          </ul>
                        )}
                      </dd>
                    </div>
                  </dl>
                </CardBody>
              </Card>
            </div>
          )}
        </CardBody>
      </Card>

      <div className="mt-6 flex items-center justify-between">
        <Button
          variant="outline"
          onClick={() => setStep((s) => Math.max(0, s - 1))}
          disabled={step === 0 || submitting}
        >
          Back
        </Button>
        <div className="flex gap-2">
          {step < steps.length - 1 ? (
            <Button onClick={() => setStep((s) => s + 1)} disabled={!canNext}>
              Next
            </Button>
          ) : (
            <>
              <Button
                variant="outline"
                onClick={() => onCreate(false)}
                loading={submitting}
                disabled={submitting}
              >
                Create subscription
              </Button>
              <Button onClick={() => onCreate(true)} loading={submitting} disabled={submitting}>
                Create and issue license
              </Button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
