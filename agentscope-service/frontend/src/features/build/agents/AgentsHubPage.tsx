import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AgentDefinition, listAgents } from '@/api/agents';
import { getRoles } from '@/api/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { cn } from '@/lib/utils';
import { useControlPlaneScope } from '@/app/ScopeContext';

type Filter = 'all' | 'managed' | 'external-application' | 'hosted-runtime' | 'disabled';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'managed', label: 'Managed' },
  { key: 'external-application', label: 'External' },
  { key: 'hosted-runtime', label: 'Hosted' },
  { key: 'disabled', label: 'Disabled' },
];

function badgeFor(a: AgentDefinition): { label: string; tone: 'info' | 'success' | 'warning' | 'default' } {
  if (a.status !== 'active') return { label: a.status || 'unknown', tone: 'warning' };
  if (a.runtimeKind === 'managed') return { label: 'managed', tone: 'success' };
  if (a.runtimeKind === 'external-application') return { label: 'external', tone: 'info' };
  if (a.runtimeKind === 'hosted-runtime') return { label: 'hosted', tone: 'default' };
  return { label: 'unbound', tone: 'warning' };
}

function bucket(a: AgentDefinition): Filter[] {
  const tags: Filter[] = ['all'];
  if (a.runtimeKind === 'managed' || a.runtimeKind === 'external-application' || a.runtimeKind === 'hosted-runtime') {
    tags.push(a.runtimeKind);
  }
  if (a.status !== 'active') tags.push('disabled');
  return tags;
}

export default function AgentsHubPage() {
  const navigate = useNavigate();
  const scope = useControlPlaneScope();
  const [filter, setFilter] = useState<Filter>('all');
  const roles = getRoles().map(role => role.toLowerCase());
  const canCreate = roles.includes('admin') || roles.includes('agent_developer');

  const agentsQ = useQuery({
    queryKey: ['product-agents', scope.tenant, scope.namespace],
    queryFn: () => listAgents(scope.tenant, scope.namespace),
  });
  const agents = agentsQ.data || [];

  const visible = useMemo(() => agents.filter((a) => bucket(a).includes(filter)), [agents, filter]);

  function openAgent(agent: AgentDefinition) {
    if (agent.runtimeKind === 'managed') {
      navigate(`/managed/agents/${encodeURIComponent(agent.id)}/settings`);
      return;
    }
    navigate(`/agent-center/agents/${encodeURIComponent(agent.id)}`);
  }

  return (
    <Page>
      <PageHeader
        title="Agents"
        description="One logical identity across Managed, External Application, and Hosted Runtime bindings."
        actions={canCreate ? <Button onClick={() => navigate('/agent-center/agents/new')}>New agent</Button> : undefined}
      />

      <div className="flex flex-wrap gap-2">
        {FILTERS.map((f) => {
          const active = filter === f.key;
          const count = agents.filter((a) => bucket(a).includes(f.key)).length;
          return (
            <button
              key={f.key}
              onClick={() => setFilter(f.key)}
              className={cn(
                'inline-flex items-center gap-2 rounded-full border px-3.5 py-2 text-sm font-semibold',
                active ? 'border-slate-900 bg-slate-900 text-white' : 'border-border bg-white text-slate-600',
              )}
            >
              {f.label}
              <span className={cn('rounded-full px-1.5 py-0.5 text-[11px]', active ? 'bg-white/20' : 'bg-slate-100')}>
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {agentsQ.isError && <p className="text-sm text-red-600">{String(agentsQ.error)}</p>}

      {agentsQ.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : visible.length === 0 ? (
        <EmptyState
          title="No agents yet"
          description="Create a Managed or Hosted Agent, or register an External Application through the SDK."
          action={canCreate ? <Button onClick={() => navigate('/agent-center/agents/new')}>Create agent</Button> : undefined}
        />
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {visible.map((a) => {
            const b = badgeFor(a);
            return (
              <Card
                key={a.id}
                className="cursor-pointer transition hover:-translate-y-0.5 hover:border-indigo-200 hover:shadow-md"
                onClick={() => openAgent(a)}
              >
                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="truncate">{a.name}</CardTitle>
                    <Badge tone={b.tone}>{b.label}</Badge>
                  </div>
                  <CardDescription className="line-clamp-2 min-h-[2.5rem]">
                    {a.description || 'No description'}
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col gap-2">
                  <code className="truncate font-mono text-[12px] text-muted-foreground">{a.agentKey || a.id}</code>
                  {a.workspaceId && (
                    <button
                      type="button"
                      className="self-start rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/workspaces/${encodeURIComponent(a.workspaceId!)}`);
                      }}
                    >
                      Workspace linked
                    </button>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </Page>
  );
}
