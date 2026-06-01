import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { apiErrorMessage, usage } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Card, CardBody, CardHeader, PageLoader, Input, Field } from '@/components/ui';

function daysAgoIso(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

export function UsagePage() {
  const { subId = '' } = useParams<{ subId: string }>();
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));

  const usageQ = useQuery({
    queryKey: ['usage', subId, from, to],
    queryFn: () => usage.forSubscription(subId, { from, to }),
    enabled: !!subId,
  });

  const seriesData = useMemo(() => {
    if (!usageQ.data) return [];
    const byTs = new Map<string, Record<string, number | string>>();
    for (const s of usageQ.data.series) {
      for (const p of s.points) {
        const ts = p.ts.slice(0, 10);
        if (!byTs.has(ts)) byTs.set(ts, { ts });
        byTs.get(ts)![s.featureKey] = ((byTs.get(ts)![s.featureKey] as number) ?? 0) + p.quantity;
      }
    }
    return Array.from(byTs.values()).sort((a, b) => String(a.ts).localeCompare(String(b.ts)));
  }, [usageQ.data]);

  const seriesKeys = useMemo(
    () => (usageQ.data?.series ?? []).map((s) => s.featureKey),
    [usageQ.data],
  );

  const quotaData = useMemo(() => {
    if (!usageQ.data) return [];
    return usageQ.data.quotas.map((q) => ({
      feature: q.featureKey,
      used: q.used,
      remaining: q.limit !== null ? Math.max(0, q.limit - q.used) : 0,
      limit: q.limit ?? 0,
    }));
  }, [usageQ.data]);

  const palette = ['#2563eb', '#16a34a', '#dc2626', '#ea580c', '#9333ea', '#0891b2'];

  return (
    <div>
      <PageHeader
        title="Usage"
        description="Feature events ingested from the customer's Docker app, with quota burn-down."
        breadcrumb={
          <Link to={`/subscriptions/${subId}`} className="hover:text-brand-700">
            Subscription
          </Link>
        }
      />

      <Card className="mb-6">
        <CardBody className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <Field label="From" htmlFor="from">
            <Input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label="To" htmlFor="to">
            <Input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </Field>
        </CardBody>
      </Card>

      {usageQ.isLoading ? (
        <PageLoader />
      ) : usageQ.isError ? (
        <div className="text-rose-600">{apiErrorMessage(usageQ.error)}</div>
      ) : (
        <div className="space-y-6">
          <Card>
            <CardHeader title="Events over time" />
            <CardBody>
              {seriesData.length === 0 ? (
                <div className="py-8 text-center text-sm text-slate-500">No usage data in this window.</div>
              ) : (
                <div className="h-80">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={seriesData}>
                      <CartesianGrid stroke="#e2e8f0" strokeDasharray="3 3" />
                      <XAxis dataKey="ts" tick={{ fontSize: 12 }} />
                      <YAxis tick={{ fontSize: 12 }} />
                      <Tooltip />
                      <Legend />
                      {seriesKeys.map((k, i) => (
                        <Line
                          key={k}
                          type="monotone"
                          dataKey={k}
                          stroke={palette[i % palette.length]}
                          strokeWidth={2}
                          dot={false}
                        />
                      ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Quota burn-down"
              description="Used vs remaining for each metered feature."
            />
            <CardBody>
              {quotaData.length === 0 ? (
                <div className="py-8 text-center text-sm text-slate-500">No quotas configured.</div>
              ) : (
                <div className="h-72">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={quotaData} layout="vertical">
                      <CartesianGrid stroke="#e2e8f0" strokeDasharray="3 3" />
                      <XAxis type="number" tick={{ fontSize: 12 }} />
                      <YAxis dataKey="feature" type="category" tick={{ fontSize: 12 }} width={140} />
                      <Tooltip />
                      <Legend />
                      <Bar dataKey="used" stackId="q" fill="#2563eb" />
                      <Bar dataKey="remaining" stackId="q" fill="#cbd5e1" />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </CardBody>
          </Card>
        </div>
      )}
    </div>
  );
}
