import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { fetchDataPlanes, fetchManagedAgents } from './api';

export default function OperateAgentsPage() {
  const agents = useQuery({ queryKey: ['v1-agents'], queryFn: fetchManagedAgents, refetchInterval: 10_000 });
  const planes = useQuery({ queryKey: ['dataplanes'], queryFn: () => fetchDataPlanes(), refetchInterval: 10_000 });

  const items = agents.data?.items || [];
  const dps = planes.data?.dataplanes || [];

  return (
    <Page>
      <PageHeader
        title="Managed agents"
        description="Data planes discovered via self-registration or Kubernetes."
      />

      {items.length === 0 && dps.length === 0 ? (
        <EmptyState
          title="No managed agents"
          description="When a data plane registers (POST /api/v1/dataplanes/register) or a BYO Agent CRD is adopted, it appears here."
        />
      ) : (
        <div className="grid gap-5 md:grid-cols-2">
          {items.map((a) => (
            <Link key={`${a.namespace}/${a.name}`} to={`/operate/agents/${encodeURIComponent(a.name)}?namespace=${encodeURIComponent(a.namespace || 'default')}`}>
              <Card className="h-full transition hover:border-indigo-200 hover:shadow-md">
                <CardHeader>
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle>{a.displayName || a.name}</CardTitle>
                    <Badge tone="info">{a.replicas || '—'}</Badge>
                  </div>
                  <CardDescription>
                    {a.namespace} · {a.runtime || a.type || 'unknown'}
                  </CardDescription>
                </CardHeader>
                <CardContent className="text-sm text-muted-foreground">
                  Active sessions: {a.activeSessions ?? 0}
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}

      {dps.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Registered instances</CardTitle>
            <CardDescription>Self-registration registry</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {dps.map((dp) => (
              <div key={dp.instanceId} className="flex items-center justify-between rounded-lg border border-border px-4 py-3 text-sm">
                <div>
                  <div className="font-medium">{dp.agentName}</div>
                  <div className="mt-0.5 text-sm text-muted-foreground">{dp.instanceId} · {dp.baseUrl}</div>
                </div>
                <Badge tone={dp.healthy ? 'success' : 'danger'}>{dp.healthy ? 'healthy' : 'stale'}</Badge>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </Page>
  );
}
