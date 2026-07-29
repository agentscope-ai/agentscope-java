import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { PressureGauge } from '@/components/PressureGauge';
import { fetchDataPlanes, fetchManagedAgent, fetchRuntimeSessions } from './api';

export default function OperateAgentDetailPage({ name }: { name: string }) {
  const [params] = useSearchParams();
  const namespace = params.get('namespace') || 'default';

  const agent = useQuery({
    queryKey: ['v1-agent', name, namespace],
    queryFn: () => fetchManagedAgent(name, namespace),
  });
  const sessions = useQuery({
    queryKey: ['runtime-sessions', name],
    queryFn: () => fetchRuntimeSessions({ agent: name }),
    refetchInterval: 10_000,
  });
  const planes = useQuery({
    queryKey: ['dataplanes', name, namespace],
    queryFn: () => fetchDataPlanes(name, namespace),
    refetchInterval: 10_000,
  });

  const a = agent.data || {};
  const caps = (a.capabilities as string[]) || [];
  const contractLevel = Number(a.contractLevel || 0);

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div>
        <Link to="/operate/agents" className="text-xs text-muted-foreground hover:text-foreground">
          ← Agents
        </Link>
        <h1 className="mt-2 text-xl font-semibold">{name}</h1>
        <p className="text-sm text-muted-foreground">
          {namespace} · {(a.runtime as string) || 'runtime unknown'} · contract L{contractLevel || '?'}
        </p>
        <div className="mt-2 flex flex-wrap gap-1">
          {caps.map((c) => (
            <Badge key={c} tone="info">{c}</Badge>
          ))}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Instances</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {(planes.data?.dataplanes || []).length === 0 ? (
              <p className="text-sm text-muted-foreground">No registered instances.</p>
            ) : (
              (planes.data?.dataplanes || []).map((dp) => (
                <div key={dp.instanceId} className="rounded-lg border border-border px-3 py-2 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{dp.instanceId}</span>
                    <Badge tone={dp.healthy ? 'success' : 'danger'}>{dp.healthy ? 'healthy' : 'stale'}</Badge>
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">{dp.baseUrl}</div>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Sessions</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {(sessions.data?.sessions || []).length === 0 ? (
              <EmptyState title="No sessions" description="Sessions appear after the poller pulls /agentscope/sessions." className="py-8" />
            ) : (
              (sessions.data?.sessions || []).map((s) => (
                <Link
                  key={s.id}
                  to={`/operate/sessions/${encodeURIComponent(s.sessionId)}`}
                  className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted/50"
                >
                  <div className="min-w-0">
                    <div className="truncate font-medium">{s.sessionId}</div>
                    <div className="text-xs uppercase text-muted-foreground">{s.phase}</div>
                  </div>
                  <PressureGauge value={s.snapshot?.contextPressure} />
                </Link>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
