import { type ReactNode, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import { Table, THead, TBody, TH, TD, EmptyState } from './ui/Table';
import { Button } from './ui/Button';
import { DURATION, EASE } from '@/lib/motion';

/** Cap the row cascade so dense tables don't take ~2s to settle: rows past this index snap in. */
const ROW_CASCADE_CAP = 10;

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  className?: string;
  width?: string;
}

export interface DataTableProps<T> {
  rows: T[] | undefined;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  loading?: boolean;
  empty?: ReactNode;
  pageSize?: number;
  onRowClick?: (row: T) => void;
  toolbar?: ReactNode;
}

export function DataTable<T>({
  rows,
  columns,
  rowKey,
  loading,
  empty,
  pageSize = 25,
  onRowClick,
  toolbar,
}: DataTableProps<T>) {
  const [page, setPage] = useState(1);
  const data = rows ?? [];
  const totalPages = Math.max(1, Math.ceil(data.length / pageSize));
  const slice = useMemo(
    () => data.slice((page - 1) * pageSize, page * pageSize),
    [data, page, pageSize],
  );

  return (
    <div className="glass-solid overflow-hidden rounded-xl ring-1 ring-slate-900/5 shadow-glass">
      {toolbar ? (
        <div className="flex items-center justify-between gap-3 border-b border-slate-900/5 px-4 py-3">
          {toolbar}
        </div>
      ) : null}
      <Table>
        <THead>
          <tr>
            {columns.map((c) => (
              <TH key={c.key} className={c.className} style={c.width ? { width: c.width } : undefined}>
                {c.header}
              </TH>
            ))}
          </tr>
        </THead>
        {loading ? (
          // Skeleton rows sized to the real layout — preserves the loading branch.
          // No entrance animation on the skeleton (per cascade-on-loaded-only rule).
          <TBody>
            {Array.from({ length: 5 }).map((_, r) => (
              <tr key={`sk-${r}`}>
                {columns.map((c) => (
                  <TD key={c.key} className={c.className}>
                    <span className="skeleton block h-4 w-[70%]" />
                  </TD>
                ))}
              </tr>
            ))}
          </TBody>
        ) : slice.length === 0 ? (
          // Empty-state row — left un-animated as required.
          <TBody>
            <tr>
              <td colSpan={columns.length}>
                <EmptyState>{empty ?? 'No results.'}</EmptyState>
              </td>
            </tr>
          </TBody>
        ) : (
          // Loaded branch: cascade rows in. We render motion.tbody / motion.tr
          // directly (TBody/TR don't forward refs) carrying the SAME classes the
          // styled TBody/TR apply, so styling is identical. The container stays
          // mounted across the loading→loaded transition and across background
          // refetches, and variants only fire on mount — so a same-data refetch
          // (rows keep their rowKey) does NOT replay. New rows on page change
          // mount and cascade in, which is the desired effect.
          <TBody>
            {slice.map((row, i) => (
              <motion.tr
                key={rowKey(row)}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{
                  duration: DURATION.base,
                  ease: EASE.out,
                  // Capped cascade: the first ROW_CASCADE_CAP rows tumble in, the rest snap in
                  // together — keeps a dense table's cascade deliberate but bounded.
                  delay: Math.min(i, ROW_CASCADE_CAP) * 0.05,
                }}
                // Same base TR styling (transition-colors hover:bg-indigo-50/40)
                // plus, when the whole row is the action, expose it to keyboard +
                // AT: focusable, button semantics, Enter/Space activation, and a
                // visible focus style. Purely additive — onRowClick is unchanged.
                className={
                  onRowClick
                    ? 'transition-colors hover:bg-indigo-50/40 cursor-pointer focus:outline-none focus-visible:bg-indigo-50/70 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-500'
                    : 'transition-colors hover:bg-indigo-50/40'
                }
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                role={onRowClick ? 'button' : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                aria-label={onRowClick ? 'Open row' : undefined}
                onKeyDown={
                  onRowClick
                    ? (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          onRowClick(row);
                        }
                      }
                    : undefined
                }
              >
                {columns.map((c) => (
                  <TD key={c.key} className={c.className}>
                    {c.render(row)}
                  </TD>
                ))}
              </motion.tr>
            ))}
          </TBody>
        )}
      </Table>
      {data.length > pageSize ? (
        <div className="flex items-center justify-between border-t border-slate-900/5 px-4 py-3 text-xs text-ink-muted">
          <span className="tabular-nums">
            Page {page} of {totalPages} · {data.length} total
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page === 1}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              disabled={page >= totalPages}
            >
              Next
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
