import { type ReactNode, useMemo, useState } from 'react';
import { Table, THead, TBody, TR, TH, TD, EmptyState } from './ui/Table';
import { Button } from './ui/Button';
import { Spinner } from './ui/Spinner';

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
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      {toolbar ? (
        <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
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
            <tr>
              <td colSpan={columns.length} className="py-10 text-center">
                <Spinner size={6} />
              </td>
            </tr>
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
                className={onRowClick ? 'cursor-pointer' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
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
        <div className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500">
          <span>
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
