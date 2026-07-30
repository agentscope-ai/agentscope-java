import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PressureGauge } from '@/components/PressureGauge';
import type { RuntimeSession } from '../api';

function formatTime(v?: string) {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString();
  } catch {
    return v;
  }
}

function busyLabel(busy?: boolean | null) {
  if (busy === true) return { text: 'busy', tone: 'warning' as const };
  if (busy === false) return { text: 'idle', tone: 'success' as const };
  return { text: 'unknown', tone: 'default' as const };
}

export function StatusStrip({ session }: { session?: RuntimeSession }) {
  const busy = busyLabel(session?.busy);
  const healthy = session?.instanceHealthy;
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Phase</CardTitle>
        </CardHeader>
        <CardContent>
          <Badge>{session?.phase || '—'}</Badge>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Busy</CardTitle>
        </CardHeader>
        <CardContent>
          <Badge tone={busy.tone}>{busy.text}</Badge>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Model</CardTitle>
        </CardHeader>
        <CardContent className="truncate text-sm text-foreground">
          {session?.model || '—'}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Pressure</CardTitle>
        </CardHeader>
        <CardContent>
          <PressureGauge value={session?.snapshot?.contextPressure} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Tokens</CardTitle>
        </CardHeader>
        <CardContent className="font-mono text-sm tabular-nums text-foreground">
          {(session?.snapshot?.totalTokens ?? 0).toLocaleString()}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Last active</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          {formatTime(session?.lastActiveAt)}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Instance</CardTitle>
        </CardHeader>
        <CardContent>
          {healthy === true ? (
            <Badge tone="success">healthy</Badge>
          ) : healthy === false ? (
            <Badge tone="danger">unhealthy</Badge>
          ) : (
            <Badge>unknown</Badge>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
