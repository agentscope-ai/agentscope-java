import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { PressureGauge } from '@/components/PressureGauge';
import { Button } from '@/components/ui/button';
import { fetchOverview, fetchOverviewTimeseries, fetchRuntimeSessions } from './api';
import { HealthBanner } from './components/HealthBanner';
import { TokenTrend } from './components/TokenTrend';
import { TopAgentsTable } from './components/TopAgentsTable';

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="font-mono text-2xl tabular-nums">{value}</CardTitle>
      </CardHeader>
    </Card>
  );
}

export default function FleetOverviewPage() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: fetchOverview,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const sessions = useQuery({
    queryKey: ['runtime-sessions'],
    queryFn: () => fetchRuntimeSessions(),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const timeseries = useQuery({
    queryKey: ['overview-timeseries', 'tokens'],
    queryFn: () => fetchOverviewTimeseries({ metric: 'tokens', bucket: '1h' }),
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
    retry: false,
  });

  const o = overview.data;
  const list = sessions.data?.sessions || [];
  const phases = o?.sessionsByPhase || {};
  const idleCount = phases.idle ?? 0;
  const healthy = o?.healthyInstanceCount ?? o?.instanceCount ?? 0;
  const stale = o?.staleInstanceCount ?? 0;

  return (
    <Page>
      <PageHeader
        title="Fleet overview"
        description="Cross-framework agent instances and runtime sessions reported into aistiod."
      />

      {overview.isError && (
        <EmptyState
          title="Overview unavailable"
          description="The /api/v1/overview endpoint did not respond. Ensure aistiod is running with the runtime store."
        />
      )}

      {o && (
        <HealthBanner
          staleDataplanes={o.staleDataplanes}
          highPressureSessions={o.highPressureSessions}
          orphanSessions={o.orphanSessions}
        />
      )}

      {o && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-8">
          <Stat label="Agents" value={o.agentCount} />
          <Stat label="Healthy instances" value={healthy} />
          <Stat label="Stale instances" value={stale} />
          <Stat label="Active sessions" value={o.activeSessionCount} />
          <Stat label="Idle sessions" value={idleCount} />
          <Stat label="Tokens (24h)" value={o.tokenUsage24h.toLocaleString()} />
          <Stat
            label="P95 pressure"
            value={
              o.p95ContextPressure != null
                ? `${Math.round(o.p95ContextPressure * 100)}%`
                : '—'
            }
          />
          <Stat label="Errors (24h)" value={o.errorCount24h ?? 0} />
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

      <TokenTrend
        points={timeseries.data?.points}
        loading={timeseries.isLoading}
        error={timeseries.isError}
      />

      <TopAgentsTable agents={o?.topAgents} />

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
        <CardHeader className="flex-row items-center justify-between space-y-0">
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
                  className="flex items-center justify-between gap-4 px-4 py-3.5 hover:bg-muted/60"
                >
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium">{s.agentName}</div>
                    <div className="truncate text-sm text-muted-foreground">{s.sessionId}</div>
                  </div>
                  <div className="flex items-center gap-4">
                    <PressureGauge value={s.snapshot?.contextPressure} />
                    <span className="text-sm uppercase text-muted-foreground">{s.phase}</span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </Page>
  );
}
