import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import type { TokenBucket } from '../api';

const CHART_H = 128;

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
  const showLabels = points.length > 0 && points.length <= 24;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Token usage (24h)</CardTitle>
        <CardDescription>Hourly sum of usage deltas (not cumulative snapshots)</CardDescription>
      </CardHeader>
      <CardContent>
        {error ? (
          <p className="text-sm text-muted-foreground">Timeseries unavailable.</p>
        ) : loading && points.length === 0 ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : points.length === 0 ? (
          <p className="text-sm text-muted-foreground">No token samples yet.</p>
        ) : (
          <div className="space-y-2">
            <div className="flex items-end gap-1.5" style={{ height: CHART_H }}>
              {points.map((p, i) => {
                const hPx = Math.max(2, Math.round(((p.totalTokens || 0) / max) * CHART_H));
                const label = p.bucketStart
                  ? new Date(p.bucketStart).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                  : `#${i}`;
                return (
                  <div
                    key={p.bucketStart || i}
                    className="min-w-0 flex-1 rounded-t bg-indigo-400/80 transition-all"
                    style={{ height: hPx }}
                    title={`${label}: ${(p.totalTokens || 0).toLocaleString()} tokens`}
                  />
                );
              })}
            </div>
            {showLabels && (
              <div className="flex gap-1.5">
                {points.map((p, i) => {
                  const label = p.bucketStart
                    ? new Date(p.bucketStart).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                    : `#${i}`;
                  return (
                    <span
                      key={`lbl-${p.bucketStart || i}`}
                      className="min-w-0 flex-1 truncate text-center text-[11px] text-muted-foreground"
                    >
                      {label}
                    </span>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
