/**
 * AURORA GLASS spinner. Indigo accent ring over a soft track. The size->rem
 * math and the `size` prop are preserved exactly; only the surface styling is
 * refined. Honors prefers-reduced-motion via the global net in index.css.
 */
export function Spinner({ size = 4 }: { size?: number }) {
  const dim = `${size * 0.25}rem`;
  return (
    <span
      role="status"
      aria-label="Loading"
      className="inline-block animate-spin rounded-full border-2 border-slate-200/80 border-t-brand-600"
      style={{ width: dim, height: dim }}
    />
  );
}

export function PageLoader() {
  return (
    <div className="flex h-64 items-center justify-center">
      <Spinner size={8} />
    </div>
  );
}
