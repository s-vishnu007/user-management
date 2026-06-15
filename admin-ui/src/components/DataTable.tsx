import { type ReactNode, useMemo, useState } from 'react';
import { Table, THead, TBody, TR, TH, TD, EmptyState } from './ui/Table';
import { Button } from './ui/Button';

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
        <TBody>
          {loading ? (
            // Skeleton rows sized to the real layout — preserves the loading branch.
            Array.from({ length: 5 }).map((_, r) => (
              <tr key={`sk-${r}`}>
                {columns.map((c) => (
                  <TD key={c.key} className={c.className}>
                    <span className="skeleton block h-4 w-[70%]" />
                  </TD>
                ))}
              </tr>
            ))
          ) : slice.length === 0 ? (
            <tr>
              <td colSpan={columns.length}>
                <EmptyState>{empty ?? 'No results.'}</EmptyState>
              </td>
            </tr>
          ) : (
            slice.map((row) => (
              <TR
                key={rowKey(row)}
                // When the whole row is the action, expose it to keyboard + AT:
                // focusable, button semantics, Enter/Space activation, and a
                // visible focus style. Purely additive — onRowClick is unchanged.
                className={
                  onRowClick
                    ? 'cursor-pointer focus:outline-none focus-visible:bg-indigo-50/70 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-500'
                    : undefined
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
              </TR>
            ))
          )}
        </TBody>
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
