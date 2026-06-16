import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AnimatePresence, motion } from 'framer-motion';
import { apiErrorMessage, licenses, newIdempotencyKey, orgs, plans, subscriptions } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Button, Card, CardBody, CardHeader, Field, Input, PageLoader, Select, StatusBadge } from '@/components/ui';
import { useToast } from '@/lib/toast';
import { triggerDownload } from '@/lib/download';
import { DURATION, EASE, successPop } from '@/lib/motion';
import { cn } from '@/lib/cn';
import type { SubscriptionOverride } from '@/lib/types';

/**
 * Per-step enter/exit motion. Direction-aware so advancing slides content in
 * from the trailing edge and stepping back slides it from the leading edge.
 * Transform + opacity only; durations stay within the interactive band so the
 * stepper feels fluid without ever blocking the Next/Back controls.
 */
const stepVariants = {
  enter: (dir: number) => ({ opacity: 0, x: dir >= 0 ? 24 : -24 }),
  center: {
    opacity: 1,
    x: 0,
    transition: { duration: DURATION.base, ease: EASE.out },
  },
  exit: (dir: number) => ({
    opacity: 0,
    x: dir >= 0 ? -24 : 24,
    transition: { duration: DURATION.fast, ease: EASE.in },
  }),
};

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
  // Presentation-only: drives the direction-aware slide between steps.
  const [direction, setDirection] = useState(0);
  const steps = ['Organization', 'Plan', 'Dates and seats', 'Overrides', 'Confirm'];

  const goNext = () => {
    setDirection(1);
    setStep((s) => s + 1);
  };
  const goBack = () => {
    setDirection(-1);
    setStep((s) => Math.max(0, s - 1));
  };

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
            <Link
              to="/orgs"
              className="rounded text-ink-muted transition-colors duration-fast hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
            >
              Organizations
            </Link>{' '}
            /
          </>
        }
      />

      <nav aria-label="Progress" className="mb-6">
        <ol className="glass-card flex flex-col gap-2 overflow-hidden rounded-2xl px-4 py-3 sm:flex-row sm:items-center sm:gap-0">
          {steps.map((label, i) => {
            const isActive = i === step;
            const isComplete = i < step;
            return (
              <li
                key={label}
                className="flex flex-1 items-center gap-3 sm:gap-0"
                aria-current={isActive ? 'step' : undefined}
              >
                <div className="flex items-center gap-3">
                  <motion.span
                    aria-hidden="true"
                    initial={false}
                    animate={{ scale: isActive ? 1.06 : 1 }}
                    transition={{ type: 'spring', stiffness: 400, damping: 26, mass: 0.8 }}
                    className={cn(
                      'relative grid h-7 w-7 shrink-0 place-items-center rounded-full text-[11px] font-semibold tabular-nums shadow-glass-sm ring-1 transition-colors duration-base',
                      isComplete
                        ? 'bg-success-500 text-white ring-success-200'
                        : isActive
                          ? 'bg-aurora-primary text-white ring-white/60 shadow-glow'
                          : 'bg-white/70 text-ink-faint ring-slate-900/5',
                    )}
                  >
                    {isComplete ? (
                      <svg
                        viewBox="0 0 20 20"
                        fill="none"
                        className="h-3.5 w-3.5"
                        aria-hidden="true"
                      >
                        <path
                          d="m5 10.5 3 3 7-7"
                          stroke="currentColor"
                          strokeWidth="2.2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    ) : (
                      i + 1
                    )}
                  </motion.span>
                  <span
                    className={cn(
                      'text-xs font-medium transition-colors duration-base sm:whitespace-nowrap',
                      isActive
                        ? 'text-ink'
                        : isComplete
                          ? 'text-ink-soft'
                          : 'text-ink-faint',
                    )}
                  >
                    {label}
                  </span>
                </div>
                {i < steps.length - 1 && (
                  <div
                    aria-hidden="true"
                    className="ml-3 hidden h-px flex-1 overflow-hidden rounded-full bg-slate-900/10 sm:block"
                  >
                    <motion.div
                      className="h-full origin-left rounded-full bg-aurora-primary"
                      initial={false}
                      animate={{ scaleX: isComplete ? 1 : 0 }}
                      transition={{ duration: DURATION.moderate, ease: EASE.out }}
                    />
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      </nav>

      <Card>
        <CardBody className="overflow-hidden">
          <AnimatePresence mode="wait" initial={false} custom={direction}>
            <motion.div
              key={step}
              custom={direction}
              variants={stepVariants}
              initial="enter"
              animate="center"
              exit="exit"
            >
          {step === 0 && (
            <div className="max-w-md">
              <h3 className="mb-3 text-sm font-semibold text-ink">Choose an organization</h3>
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
              <h3 className="mb-3 text-sm font-semibold text-ink">Choose a plan</h3>
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
                        aria-pressed={selected}
                        onClick={() => setState((s) => ({ ...s, planId: p.id }))}
                        className={cn(
                          'group relative rounded-xl border p-4 text-left shadow-glass-sm transition-all duration-fast ease-out-quint',
                          'focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white',
                          'hover:-translate-y-0.5 hover:shadow-glass',
                          selected
                            ? 'border-indigo-300 bg-gradient-to-br from-indigo-50/90 to-violet-50/80 ring-1 ring-indigo-300'
                            : 'border-white/60 bg-white/70 backdrop-blur-glass hover:bg-white/85',
                        )}
                      >
                        {selected && (
                          <span
                            aria-hidden="true"
                            className="absolute right-3 top-3 grid h-5 w-5 place-items-center rounded-full bg-aurora-primary text-white shadow-glow"
                          >
                            <svg viewBox="0 0 20 20" fill="none" className="h-3 w-3">
                              <path
                                d="m5 10.5 3 3 7-7"
                                stroke="currentColor"
                                strokeWidth="2.4"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                              />
                            </svg>
                          </span>
                        )}
                        <div className="flex items-center justify-between gap-2 pr-7">
                          <div className="font-semibold text-ink">{p.name}</div>
                          <StatusBadge status={p.active === false ? 'RETIRED' : 'ACTIVE'} />
                        </div>
                        <div className="mt-1 text-xs text-ink-muted">
                          <code className="font-mono">{p.code}</code>
                        </div>
                        {p.description ? (
                          <p className="mt-2 text-sm text-ink-soft">{p.description}</p>
                        ) : null}
                        <div className="mt-3 text-xs text-ink-muted">
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
                <h3 className="text-sm font-semibold text-ink">Overrides (optional)</h3>
                <Button size="sm" variant="outline" onClick={addOverride}>
                  Add override
                </Button>
              </div>
              {state.overrides.length === 0 ? (
                <div className="rounded-xl border border-dashed border-slate-300/80 bg-white/40 p-5 text-sm text-ink-muted">
                  Plan entitlements will be used as-is. Add overrides for per-customer terms.
                </div>
              ) : (
                <div className="space-y-2">
                  {state.overrides.map((o, i) => (
                    <div
                      key={i}
                      className="grid grid-cols-1 items-end gap-2 rounded-xl border border-white/60 bg-white/60 p-3 shadow-glass-sm backdrop-blur-glass md:grid-cols-12"
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
              <div className="mb-3 flex items-center gap-2">
                <motion.span
                  aria-hidden="true"
                  variants={successPop}
                  initial="hidden"
                  animate="show"
                  className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-success-500 text-white shadow-glow"
                >
                  <svg viewBox="0 0 20 20" fill="none" className="h-3.5 w-3.5">
                    <path
                      d="m5 10.5 3 3 7-7"
                      stroke="currentColor"
                      strokeWidth="2.4"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </motion.span>
                <h3 className="text-sm font-semibold text-ink">Confirm</h3>
              </div>
              <Card>
                <CardHeader title="Summary" />
                <CardBody>
                  <dl className="grid grid-cols-1 gap-y-4 text-sm sm:grid-cols-2">
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Organization
                      </dt>
                      <dd className="mt-0.5 font-medium text-ink">{selectedOrg?.name ?? state.orgId}</dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Plan
                      </dt>
                      <dd className="mt-0.5 font-medium text-ink">{selectedPlan?.name ?? state.planId}</dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Term
                      </dt>
                      <dd className="mt-0.5 text-ink-soft tabular-nums">
                        {state.startsAt} → {state.endsAt}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Seats
                      </dt>
                      <dd className="mt-0.5 text-ink-soft tabular-nums">{state.seats}</dd>
                    </div>
                    <div className="sm:col-span-2">
                      <dt className="text-xs font-medium uppercase tracking-wide text-ink-faint">
                        Overrides
                      </dt>
                      <dd className="mt-0.5 text-ink-soft">
                        {state.overrides.length === 0 ? (
                          <span className="text-ink-muted">None</span>
                        ) : (
                          <ul className="list-inside list-disc">
                            {state.overrides.map((o, i) => (
                              <li key={i}>
                                <code className="font-mono text-xs">{o.type}</code> · {o.key}
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
            </motion.div>
          </AnimatePresence>
        </CardBody>
      </Card>

      <div className="mt-6 flex items-center justify-between">
        <Button
          variant="outline"
          onClick={goBack}
          disabled={step === 0 || submitting}
        >
          Back
        </Button>
        <div className="flex gap-2">
          {step < steps.length - 1 ? (
            <Button onClick={goNext} disabled={!canNext}>
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
