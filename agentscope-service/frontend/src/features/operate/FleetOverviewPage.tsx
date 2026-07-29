import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { PressureGauge } from '@/components/PressureGauge';
import { Button } from '@/components/ui/button';
import { fetchOverview, fetchRuntimeSessions } from './api';

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{value}</CardTitle>
      </CardHeader>
    </Card>
  );
}

export default function FleetOverviewPage() {
  const overview = useQuery({ queryKey: ['overview'], queryFn: fetchOverview, refetchInterval: 10_000 });
  const sessions = useQuery({
    queryKey: ['runtime-sessions'],
    queryFn: () => fetchRuntimeSessions(),
    refetchInterval: 10_000,
  });

  const o = overview.data;
  const list = sessions.data?.sessions || [];

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Fleet overview</h1>
        <p className="text-sm text-muted-foreground">
          Cross-framework agent instances and runtime sessions reported into aistiod.
        </p>
      </div>

      {overview.isError && (
        <EmptyState
          title="Overview unavailable"
          description="The /api/v1/overview endpoint did not respond. Ensure aistiod is running with the runtime store."
        />
      )}

      {o && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label="Managed agents" value={o.agentCount} />
          <Stat label="Instances" value={o.instanceCount} />
          <Stat label="Active sessions" value={o.activeSessionCount} />
          <Stat label="Tokens (24h)" value={o.tokenUsage24h.toLocaleString()} />
        </div>
      )}

      {o && (
        <Card>
          <CardHeader>
            <CardTitle>Average context pressure</CardTitle>
            <CardDescription>Across sessions with a Level-1 snapshot</CardDescription>
          </CardHeader>
          <CardContent>
            <PressureGauge value={o.avgContextPressure} />
          </CardContent>
        </Card>
      )}

      {!overview.isLoading && o && o.dataplaneCount === 0 && o.agentCount === 0 && (
        <EmptyState
          title="No data planes registered"
          description="Start a data plane that implements /agentscope/* and self-registers with aistiod, or connect Kubernetes for CRD discovery."
          action={
            <Button asChild variant="outline">
              <Link to="/operate/agents">Open agents</Link>
            </Button>
          }
        />
      )}

      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <div>
            <CardTitle>Recent sessions</CardTitle>
            <CardDescription>Runtime store, refreshed every 10s</CardDescription>
          </div>
          <Button asChild variant="outline" size="sm">
            <Link to="/operate/sessions">View all</Link>
          </Button>
        </CardHeader>
        <CardContent>
          {list.length === 0 ? (
            <p className="text-sm text-muted-foreground">No runtime sessions yet.</p>
          ) : (
            <div className="divide-y divide-border rounded-lg border border-border">
              {list.slice(0, 8).map((s) => (
                <Link
                  key={s.id}
                  to={`/operate/sessions/${encodeURIComponent(s.sessionId)}`}
                  className="flex items-center justify-between gap-4 px-4 py-3 hover:bg-muted/60"
                >
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium">{s.agentName}</div>
                    <div className="truncate text-xs text-muted-foreground">{s.sessionId}</div>
                  </div>
                  <div className="flex items-center gap-4">
                    <PressureGauge value={s.snapshot?.contextPressure} />
                    <span className="text-xs uppercase text-muted-foreground">{s.phase}</span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
