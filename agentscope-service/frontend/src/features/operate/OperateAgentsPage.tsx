import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { fetchDataPlanes, fetchManagedAgents } from './api';

type HealthFilter = '' | 'healthy' | 'stale';

function parseHealth(v: string | null): HealthFilter {
  if (v === 'healthy' || v === 'stale') return v;
  return '';
}

export default function OperateAgentsPage() {
  const [params, setParams] = useSearchParams();
  const [health, setHealth] = useState<HealthFilter>(() => parseHealth(params.get('health')));
  const [q, setQ] = useState('');

  const agents = useQuery({ queryKey: ['v1-agents'], queryFn: fetchManagedAgents, refetchInterval: 10_000 });
  const planes = useQuery({
    queryKey: ['dataplanes-all'],
    queryFn: () => fetchDataPlanes(),
    refetchInterval: 10_000,
    enabled: health !== '',
  });

  useEffect(() => {
    setHealth(parseHealth(params.get('health')));
  }, [params]);

  function updateHealth(next: HealthFilter) {
    const nextParams = new URLSearchParams(params);
    if (next) nextParams.set('health', next);
    else nextParams.delete('health');
    setParams(nextParams, { replace: true });
  }

  const items = agents.data?.items || [];

  const agentKeysByHealth = useMemo(() => {
    const wantHealthy = health === 'healthy';
    const keys = new Set<string>();
    for (const dp of planes.data?.dataplanes || []) {
      const ns = dp.namespace || 'default';
      const key = `${ns}/${dp.agentName}`;
      if (wantHealthy ? dp.healthy : !dp.healthy) {
        keys.add(key);
      }
    }
    return keys;
  }, [planes.data, health]);

  const filtered = useMemo(() => {
    let list = items;
    if (health) {
      list = list.filter((a) => agentKeysByHealth.has(`${a.namespace || 'default'}/${a.name}`));
    }
    const needle = q.trim().toLowerCase();
    if (!needle) return list;
    return list.filter((a) => {
      const hay = [a.name, a.displayName, a.namespace, a.runtime, a.type]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return hay.includes(needle);
    });
  }, [items, q, health, agentKeysByHealth]);

  const healthLabel =
    health === 'healthy' ? 'with healthy instances' : health === 'stale' ? 'with stale instances' : '';

  return (
    <Page>
      <PageHeader
        title="Managed agents"
        description="Data planes discovered via self-registration or Kubernetes."
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">Instance health</span>
          <select
            className="h-10 min-w-[12rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={health}
            onChange={(e) => updateHealth(parseHealth(e.target.value))}
          >
            <option value="">All agents</option>
            <option value="healthy">Healthy instances</option>
            <option value="stale">Stale instances</option>
          </select>
        </label>

        {(items.length > 0 || health) && (
          <label className="grid min-w-[16rem] flex-1 gap-1 text-sm">
            <span className="text-muted-foreground">Search</span>
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search agents by name, namespace, or runtime…"
            />
          </label>
        )}
      </div>

      {items.length === 0 ? (
        <EmptyState
          title="No managed agents"
          description="When a data plane registers (POST /api/v1/dataplanes/register) or a BYO Agent CRD is adopted, it appears here."
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          title="No matches"
          description={
            health
              ? `No agents ${healthLabel}${q.trim() ? ` matching “${q.trim()}”` : ''}.`
              : `No agents match “${q.trim()}”.`
          }
        />
      ) : (
        <div className="grid gap-5 md:grid-cols-2">
          {filtered.map((a) => (
            <Link
              key={`${a.namespace}/${a.name}`}
              to={`/operate/agents/${encodeURIComponent(a.name)}?namespace=${encodeURIComponent(a.namespace || 'default')}${health ? `&tab=instances` : ''}`}
            >
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
    </Page>
  );
}
