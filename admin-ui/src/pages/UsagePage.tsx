import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipProps,
} from 'recharts';
import { apiErrorMessage, usage } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import { Card, CardBody, CardHeader, PageLoader, Input, Field } from '@/components/ui';
import { chartReveal, fadeRise, staggerContainer } from '@/lib/motion';

function daysAgoIso(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

/**
 * AURORA GLASS chart tooltip — recharts' default swatch reads the series `fill`,
 * which for gradient-filled areas resolves to a url() ref and renders blank, so
 * we render the visible stroke color ourselves on a frosted `.glass-pop` panel.
 */
function ChartTooltip({ active, payload, label }: TooltipProps<number, string>) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="glass-pop min-w-[9rem] rounded-xl px-3 py-2 text-xs shadow-glass-lg">
      {label != null && <div className="mb-1 font-medium text-ink">{String(label)}</div>}
      <ul className="space-y-1">
        {payload.map((p, i) => (
          <li key={`${p.dataKey ?? i}`} className="flex items-center justify-between gap-3">
            <span className="flex items-center gap-1.5 text-ink-muted">
              <span
                className="inline-block h-2 w-2 rounded-full"
                style={{ backgroundColor: (p.color ?? p.stroke ?? p.fill) as string }}
              />
              {String(p.name ?? p.dataKey)}
            </span>
            <span className="font-medium tabular-nums text-ink">{String(p.value)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function UsagePage() {
  const { subId = '' } = useParams<{ subId: string }>();
  const [from, setFrom] = useState(daysAgoIso(30));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));

  const usageQ = useQuery({
    queryKey: ['usage', subId, from, to],
    queryFn: () => usage.forSubscription(subId, { from, to }),
    enabled: !!subId,
    // Keep the previous range's data visible (isLoading stays false) while a new date range loads, so
    // the chart cascade wrapper stays mounted and does NOT replay its entrance on every date change
    // (transitions re-audit: the wrapper lived inside the isLoading branch and remounted otherwise).
    placeholderData: keepPreviousData,
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

  const palette = ['#6366f1', '#22d3ee', '#8b5cf6', '#10b981', '#f59e0b', '#ec4899'];

  return (
    <div>
      <PageHeader
        title="Usage"
        description="Feature events ingested from the customer's Docker app, with quota burn-down."
        breadcrumb={
          <Link
            to={`/subscriptions/${subId}`}
            className="rounded-sm text-ink-faint transition-colors hover:text-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
          >
            Subscription
          </Link>
        }
      />

      <motion.div variants={fadeRise} initial="hidden" animate="show">
        <Card className="mb-6">
          <CardBody className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <Field label="From" htmlFor="from">
              <Input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </Field>
            <Field label="To" htmlFor="to">
              <Input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            </Field>
          </CardBody>
        </Card>
      </motion.div>

      {usageQ.isLoading ? (
        <PageLoader />
      ) : usageQ.isError ? (
        <div className="rounded-xl border border-danger-200 bg-danger-50/70 px-4 py-3 text-sm text-danger-700">
          {apiErrorMessage(usageQ.error)}
        </div>
      ) : (
        <motion.div
          className="space-y-6"
          variants={staggerContainer}
          initial="hidden"
          animate="show"
        >
          <motion.div variants={chartReveal}>
            <Card>
              <CardHeader title="Events over time" />
              <CardBody>
                {seriesData.length === 0 ? (
                  <div className="rounded-xl border border-dashed border-slate-300/70 bg-white/40 px-4 py-10 text-center text-sm text-ink-muted">
                    No usage data in this window.
                  </div>
                ) : (
                  <div className="h-80" role="img" aria-label="Area chart of feature events ingested over the selected time window, with one trend line per metered feature.">
                    <ResponsiveContainer width="100%" height="100%">
                      <AreaChart data={seriesData} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                        <defs>
                          {seriesKeys.map((k, i) => {
                            const c = palette[i % palette.length];
                            return (
                              <linearGradient
                                key={k}
                                id={`usage-fill-${i}`}
                                x1="0"
                                y1="0"
                                x2="0"
                                y2="1"
                              >
                                <stop offset="0%" stopColor={c} stopOpacity={0.28} />
                                <stop offset="100%" stopColor={c} stopOpacity={0} />
                              </linearGradient>
                            );
                          })}
                        </defs>
                        <CartesianGrid vertical={false} stroke="#eef0f5" />
                        <XAxis
                          dataKey="ts"
                          tickLine={false}
                          axisLine={false}
                          tick={{ fontSize: 12, fill: '#64748b' }}
                        />
                        <YAxis
                          tickLine={false}
                          axisLine={false}
                          tick={{ fontSize: 12, fill: '#64748b' }}
                        />
                        <Tooltip
                          content={<ChartTooltip />}
                          cursor={{ stroke: '#c7d2fe', strokeWidth: 1 }}
                        />
                        <Legend wrapperStyle={{ fontSize: 12, color: '#475569' }} />
                        {seriesKeys.map((k, i) => (
                          <Area
                            key={k}
                            type="monotone"
                            dataKey={k}
                            stroke={palette[i % palette.length]}
                            strokeWidth={2.5}
                            fill={`url(#usage-fill-${i})`}
                            dot={false}
                            activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }}
                          />
                        ))}
                      </AreaChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </CardBody>
            </Card>
          </motion.div>

          <motion.div variants={chartReveal}>
            <Card>
              <CardHeader
                title="Quota burn-down"
                description="Used vs remaining for each metered feature."
              />
              <CardBody>
                {quotaData.length === 0 ? (
                  <div className="rounded-xl border border-dashed border-slate-300/70 bg-white/40 px-4 py-10 text-center text-sm text-ink-muted">
                    No quotas configured.
                  </div>
                ) : (
                  <div className="h-72" role="img" aria-label="Horizontal stacked bar chart showing used versus remaining quota for each metered feature.">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart
                        data={quotaData}
                        layout="vertical"
                        margin={{ top: 4, right: 8, bottom: 0, left: 0 }}
                      >
                        <CartesianGrid horizontal={false} stroke="#eef0f5" />
                        <XAxis
                          type="number"
                          tickLine={false}
                          axisLine={false}
                          tick={{ fontSize: 12, fill: '#64748b' }}
                        />
                        <YAxis
                          dataKey="feature"
                          type="category"
                          tickLine={false}
                          axisLine={false}
                          tick={{ fontSize: 12, fill: '#64748b' }}
                          width={140}
                        />
                        <Tooltip
                          content={<ChartTooltip />}
                          cursor={{ fill: 'rgba(99,102,241,0.06)' }}
                        />
                        <Legend wrapperStyle={{ fontSize: 12, color: '#475569' }} />
                        <Bar dataKey="used" stackId="q" fill="#6366f1" barSize={18} radius={[0, 0, 0, 0]} />
                        <Bar
                          dataKey="remaining"
                          stackId="q"
                          fill="#e2e8f0"
                          barSize={18}
                          radius={[0, 6, 6, 0]}
                        />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </CardBody>
            </Card>
          </motion.div>
        </motion.div>
      )}
    </div>
  );
}
