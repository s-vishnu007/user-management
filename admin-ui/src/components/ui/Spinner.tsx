export function Spinner({ size = 4 }: { size?: number }) {
  const dim = `${size * 0.25}rem`;
  return (
    <span
      className="inline-block animate-spin rounded-full border-2 border-slate-300 border-t-brand-600"
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
