import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { licenses, orgs, plans, subscriptions } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/cn';
import { fadeRise, hoverLift, staggerContainer } from '@/lib/motion';
import { useMemo, type ReactNode } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  type TooltipProps,
  XAxis,
  YAxis,
} from 'recharts';
import type { License } from '@/lib/types';

/** Premium KPI card: glass surface, aurora icon chip, gradient metric, hover-lift. */
function Stat({
  label,
  value,
  hint,
  loading,
  icon,
  accent,
}: {
  label: string;
  value: string | number;
  hint?: string;
  loading?: boolean;
  icon: ReactNode;
  accent: string;
}) {
  return (
    <motion.div variants={fadeRise} {...hoverLift} className="h-full">
      {/* KPI cards use a near-opaque solid fill with NO backdrop-filter so the dense
          4-up grid doesn't stack 4 live blur surfaces over the drifting aurora mesh
          (design-system rule: cap overlapping backdrop-filter at ~2/region). Blur is
          reserved for the larger chart + quick-actions panels below. `!` overrides the
          Card base utilities deterministically (cn() is plain clsx, not tw-merge). */}
      <Card className="relative h-full overflow-hidden !bg-white/90 !backdrop-filter-none">
        {/* decorative accent wash, low-chroma so text stays AA */}
        <div
          aria-hidden
          className={cn(
            'pointer-events-none absolute -right-8 -top-10 h-28 w-28 rounded-full opacity-60 blur-2xl',
            accent,
          )}
        />
        <CardBody className="relative flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {label}
            </div>
            <div className="mt-2 font-display text-metric tabular-nums text-ink">
              {loading ? (
                <span className="skeleton inline-block h-9 w-20 rounded-lg align-middle" />
              ) : (
                value
              )}
            </div>
            {hint ? <div className="mt-1.5 text-xs text-ink-faint">{hint}</div> : null}
          </div>
          <span
            aria-hidden
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-aurora-chip text-indigo-600 shadow-glass-sm ring-1 ring-white/60"
          >
            {icon}
          </span>
        </CardBody>
      </Card>
    </motion.div>
  );
}

/** Custom glass tooltip — reads the series fill via payload so the gradient
 *  doesn't break the default swatch. */
function ChartTooltip({ active, payload, label }: TooltipProps<number, string>) {
  if (!active || !payload || payload.length === 0) return null;
  const point = payload[0];
  return (
    <div className="glass-pop rounded-xl px-3 py-2 shadow-glass-lg ring-1 ring-slate-900/5">
      <div className="text-xs font-medium text-ink-faint">{label}</div>
      <div className="mt-0.5 flex items-center gap-2">
        <span
          aria-hidden
          className="h-2.5 w-2.5 rounded-full"
          style={{ backgroundColor: '#6366f1' }}
        />
        <span className="text-sm font-semibold tabular-nums text-ink">
          {point.value} {point.value === 1 ? 'license' : 'licenses'}
        </span>
      </div>
    </div>
  );
}

const ICON = {
  orgs: (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 21V5a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v16M13 9h6a1 1 0 0 1 1 1v11M6 8h2m-2 4h2m-2 4h2m9-4h1m-1 4h1" />
    </svg>
  ),
  plans: (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4 7h16M4 12h16M4 17h10" />
      <rect x="3" y="4" width="18" height="16" rx="2" />
    </svg>
  ),
  thisMonth: (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
      <rect x="3" y="4" width="18" height="17" rx="2" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 9h18M8 3v3m8-3v3m-7 8 2 2 4-4" />
    </svg>
  ),
  active: (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
    </svg>
  ),
} as const;

type QuickAction = { to: string; label: string; helper: string; icon: ReactNode };

const QUICK_ACTIONS: QuickAction[] = [
  {
    to: '/orgs',
    label: 'Manage organizations',
    helper: 'Customers & tenants',
    icon: ICON.orgs,
  },
  {
    to: '/plans',
    label: 'Manage plans',
    helper: 'Entitlements & pricing',
    icon: ICON.plans,
  },
  {
    to: '/subscriptions/new',
    label: 'Provision new subscription',
    helper: 'Issue access to a customer',
    icon: (
      <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 5v14m7-7H5" />
      </svg>
    ),
  },
  {
    to: '/keys',
    label: 'Rotate signing keys',
    helper: 'Ed25519 key management',
    icon: (
      <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 5.25a3 3 0 0 1 3 3m3 0a6 6 0 0 1-7.03 5.91l-2.47 2.47a2.12 2.12 0 0 1-1.5.62H9v1.5a.75.75 0 0 1-.75.75H6.75v1.5a.75.75 0 0 1-.75.75H3.75A1.5 1.5 0 0 1 2.25 21v-2.69c0-.4.16-.78.44-1.06l5.4-5.4A6 6 0 1 1 21.75 8.25Z" />
      </svg>
    ),
  },
];

export function DashboardPage() {
  const { user } = useAuth();
  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });
  const plansQ = useQuery({ queryKey: ['plans'], queryFn: plans.list });

  // The /licenses endpoint is now subscription-scoped (the tenant-leak fix), so there is no
  // unscoped global list. Aggregate license activity client-side by walking each org's
  // subscriptions and fetching that subscription's licenses. (Flagged: a backend aggregate
  // endpoint would be cleaner — see crossCuttingNotes.)
  const licensesQ = useQuery<License[]>({
    queryKey: ['dashboard', 'licenses'],
    enabled: !!orgsQ.data,
    queryFn: async () => {
      const orgList = orgsQ.data ?? [];
      const subArrays = await Promise.all(
        orgList.map((o) => subscriptions.listForOrg(o.id).catch(() => [])),
      );
      const subs = subArrays.flat();
      const licArrays = await Promise.all(
        subs.map((s) => licenses.listForSubscription(s.id).catch(() => [])),
      );
      return licArrays.flat();
    },
  });

  const licensesLoading = orgsQ.isLoading || licensesQ.isLoading;

  const issuedByMonth = useMemo(() => {
    const data = licensesQ.data ?? [];
    const byMonth = new Map<string, number>();
    for (const lic of data) {
      const d = new Date(lic.issuedAt);
      if (Number.isNaN(d.getTime())) continue;
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      byMonth.set(key, (byMonth.get(key) ?? 0) + 1);
    }
    return Array.from(byMonth.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .slice(-6)
      .map(([month, count]) => ({ month, count }));
  }, [licensesQ.data]);

  const licensesThisMonth = useMemo(() => {
    const now = new Date();
    const start = new Date(now.getFullYear(), now.getMonth(), 1);
    return (licensesQ.data ?? []).filter((l) => new Date(l.issuedAt) >= start).length;
  }, [licensesQ.data]);

  const activeLicenses = useMemo(
    () => (licensesQ.data ?? []).filter((l) => !l.revokedAt && new Date(l.expiresAt) > new Date()).length,
    [licensesQ.data],
  );

  return (
    <div>
      <PageHeader
        title={`Welcome${user?.fullName ? `, ${user.fullName}` : ''}`}
        description="A snapshot of customers, subscriptions, and license activity."
        breadcrumb={
          <span className="inline-flex items-center gap-1.5">
            <span
              aria-hidden
              className="pulse-ring h-1.5 w-1.5 rounded-full bg-success-500"
            />
            Live control panel
          </span>
        }
      />

      <motion.div
        variants={staggerContainer}
        initial="hidden"
        animate="show"
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4"
      >
        <Stat
          label="Organizations"
          value={orgsQ.data?.length ?? 0}
          loading={orgsQ.isLoading}
          icon={ICON.orgs}
          accent="bg-indigo-300/40"
        />
        <Stat
          label="Plans"
          value={plansQ.data?.length ?? 0}
          loading={plansQ.isLoading}
          icon={ICON.plans}
          accent="bg-violet-300/40"
        />
        <Stat
          label="Licenses this month"
          value={licensesThisMonth}
          loading={licensesLoading}
          icon={ICON.thisMonth}
          accent="bg-cyan-300/40"
        />
        <Stat
          label="Active licenses"
          value={activeLicenses}
          hint="Not expired, not revoked"
          loading={licensesLoading}
          icon={ICON.active}
          accent="bg-success-200/60"
        />
      </motion.div>

      <motion.div
        variants={staggerContainer}
        initial="hidden"
        animate="show"
        className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-3"
      >
        <motion.div variants={fadeRise} className="lg:col-span-2">
          <Card className="h-full overflow-hidden">
            <CardHeader
              title="Licenses issued"
              description="Issuance trend over the last 6 months."
            />
            <CardBody>
              {issuedByMonth.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
                  <span
                    aria-hidden
                    className="flex h-12 w-12 items-center justify-center rounded-full bg-aurora-chip text-indigo-600 ring-1 ring-white/60"
                  >
                    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth={1.8}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M3 3v18h18M7 14l3-3 3 3 5-6" />
                    </svg>
                  </span>
                  <div className="text-sm font-medium text-ink">No license data yet.</div>
                  <p className="max-w-xs text-xs text-ink-muted">
                    Issued licenses will appear here once subscriptions start producing them.
                  </p>
                </div>
              ) : (
                <div className="h-72">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={issuedByMonth} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
                      <defs>
                        <linearGradient id="dashIssuedFill" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="#6366f1" stopOpacity={0.28} />
                          <stop offset="100%" stopColor="#6366f1" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid vertical={false} stroke="#eef0f5" />
                      <XAxis
                        dataKey="month"
                        tickLine={false}
                        axisLine={false}
                        tick={{ fontSize: 12, fill: '#64748b' }}
                      />
                      <YAxis
                        tickLine={false}
                        axisLine={false}
                        allowDecimals={false}
                        tick={{ fontSize: 12, fill: '#64748b' }}
                      />
                      <Tooltip
                        content={<ChartTooltip />}
                        cursor={{ stroke: '#c7d2fe', strokeWidth: 1.5 }}
                      />
                      <Area
                        type="monotone"
                        dataKey="count"
                        stroke="#6366f1"
                        strokeWidth={2.5}
                        fill="url(#dashIssuedFill)"
                        dot={false}
                        activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </CardBody>
          </Card>
        </motion.div>

        <motion.div variants={fadeRise}>
          <Card className="h-full">
            <CardHeader title="Quick actions" />
            <CardBody className="space-y-2">
              {QUICK_ACTIONS.map((action) => (
                <Link
                  key={action.to}
                  to={action.to}
                  className="group flex items-center gap-3 rounded-xl border border-white/60 bg-white/50 px-3 py-2.5 shadow-glass-sm ring-1 ring-slate-900/5 transition-all duration-fast ease-out-quint hover:-translate-y-0.5 hover:bg-white/80 hover:shadow-glass focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
                >
                  <span
                    aria-hidden
                    className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-aurora-chip text-indigo-600 ring-1 ring-white/60"
                  >
                    {action.icon}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-ink">
                      {action.label}
                    </span>
                    <span className="block truncate text-xs text-ink-muted">{action.helper}</span>
                  </span>
                  <svg
                    aria-hidden
                    viewBox="0 0 24 24"
                    className="h-4 w-4 shrink-0 text-ink-ghost transition-transform duration-fast ease-out-quint group-hover:translate-x-0.5 group-hover:text-indigo-500"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={2}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="m9 5 7 7-7 7" />
                  </svg>
                </Link>
              ))}
            </CardBody>
          </Card>
        </motion.div>
      </motion.div>
    </div>
  );
}
