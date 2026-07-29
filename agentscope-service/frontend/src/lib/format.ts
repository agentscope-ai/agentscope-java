export function formatNumber(n?: number | null): string {
  if (n == null || Number.isNaN(n)) return '—';
  return n.toLocaleString();
}

export function formatPercent(ratio?: number | null): string {
  if (ratio == null || Number.isNaN(ratio)) return '—';
  return `${Math.round(Math.max(0, Math.min(1, ratio)) * 100)}%`;
}

export function formatRelative(iso?: string | null): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const sec = Math.round(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 48) return `${hr}h ago`;
  return new Date(t).toLocaleString();
}
