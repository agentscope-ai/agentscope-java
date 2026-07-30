import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import type { TokenBucket } from '../api';

export function TokenTrend({
  points = [],
  loading,
  error,
}: {
  points?: TokenBucket[];
  loading?: boolean;
  error?: boolean;
}) {
  const max = Math.max(1, ...points.map((p) => p.totalTokens || 0));

  return (
    <Card>
      <CardHeader>
        <CardTitle>Token usage (24h)</CardTitle>
        <CardDescription>Hourly totals from /overview/timeseries</CardDescription>
      </CardHeader>
      <CardContent>
        {error ? (
          <p className="text-sm text-muted-foreground">Timeseries unavailable.</p>
        ) : loading && points.length === 0 ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : points.length === 0 ? (
          <p className="text-sm text-muted-foreground">No token samples yet.</p>
        ) : (
          <div className="flex h-32 items-end gap-1.5">
            {points.map((p, i) => {
              const h = Math.max(2, Math.round(((p.totalTokens || 0) / max) * 100));
              const label = p.bucketStart
                ? new Date(p.bucketStart).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                : `#${i}`;
              return (
                <div key={p.bucketStart || i} className="flex min-w-0 flex-1 flex-col items-center gap-1.5">
                  <div
                    className="w-full rounded-t bg-indigo-400/80 transition-all"
                    style={{ height: `${h}%` }}
                    title={`${label}: ${(p.totalTokens || 0).toLocaleString()} tokens`}
                  />
                  {points.length <= 12 && (
                    <span className="truncate text-[11px] text-muted-foreground">{label}</span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
