import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { licenses, orgs, plans } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Card, CardBody, CardHeader, Spinner } from '@/components/ui';
import { useAuth } from '@/lib/auth';
import { useMemo } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

function Stat({
  label,
  value,
  hint,
  loading,
}: {
  label: string;
  value: string | number;
  hint?: string;
  loading?: boolean;
}) {
  return (
    <Card>
      <CardBody>
        <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
        <div className="mt-2 text-3xl font-semibold text-slate-900">
          {loading ? <Spinner /> : value}
        </div>
        {hint ? <div className="mt-1 text-xs text-slate-400">{hint}</div> : null}
      </CardBody>
    </Card>
  );
}

export function DashboardPage() {
  const { user } = useAuth();
  const orgsQ = useQuery({ queryKey: ['orgs'], queryFn: orgs.list });
  const plansQ = useQuery({ queryKey: ['plans'], queryFn: plans.list });
  const licensesQ = useQuery({ queryKey: ['licenses'], queryFn: () => licenses.list() });

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
        title={`Welcome${user?.displayName ? `, ${user.displayName}` : ''}`}
        description="A snapshot of customers, subscriptions, and license activity."
      />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Organizations" value={orgsQ.data?.length ?? 0} loading={orgsQ.isLoading} />
        <Stat label="Plans" value={plansQ.data?.length ?? 0} loading={plansQ.isLoading} />
        <Stat
          label="Licenses this month"
          value={licensesThisMonth}
          loading={licensesQ.isLoading}
        />
        <Stat
          label="Active licenses"
          value={activeLicenses}
          hint="Not expired, not revoked"
          loading={licensesQ.isLoading}
        />
      </div>

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="Licenses issued (last 6 months)" />
          <CardBody>
            {issuedByMonth.length === 0 ? (
              <div className="py-10 text-center text-sm text-slate-500">No license data yet.</div>
            ) : (
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={issuedByMonth}>
                    <CartesianGrid stroke="#e2e8f0" strokeDasharray="3 3" />
                    <XAxis dataKey="month" tick={{ fontSize: 12 }} />
                    <YAxis tick={{ fontSize: 12 }} allowDecimals={false} />
                    <Tooltip />
                    <Bar dataKey="count" fill="#2563eb" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Quick actions" />
          <CardBody className="space-y-2 text-sm">
            <Link className="block rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50" to="/orgs">
              Manage organizations
            </Link>
            <Link className="block rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50" to="/plans">
              Manage plans
            </Link>
            <Link
              className="block rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50"
              to="/subscriptions/new"
            >
              Provision new subscription
            </Link>
            <Link className="block rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50" to="/keys">
              Rotate signing keys
            </Link>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
