/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Plus, Search } from 'lucide-react';
import { AgentDefinition, listAgents } from '@/api/agents';
import { listAgentInstances } from '@/api/runtimeControl';
import { frameworkSummary } from './frameworkSummary';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { cn } from '@/lib/utils';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { agentDetailPath } from './agentNavigation';

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
  const [query, setQuery] = useState('');
  const roles = scope.roles;
  const canCreate = roles.includes('admin') || roles.includes('developer');

  const agentsQ = useQuery({
    queryKey: ['product-agents', scope.tenant, scope.namespace],
    queryFn: () => listAgents(scope.tenant, scope.namespace),
  });
  const instancesQ = useQuery({ queryKey: ['agent-instances', scope.tenant, scope.namespace], queryFn: () => listAgentInstances(scope.tenant, scope.namespace) });
  const frameworks = useMemo(() => {
    const byAgent = new Map<string, NonNullable<typeof instancesQ.data>['items']>();
    for (const instance of instancesQ.data?.items ?? []) byAgent.set(instance.agentId, [...(byAgent.get(instance.agentId) ?? []), instance]);
    return new Map([...byAgent].map(([id, instances]) => [id, frameworkSummary(instances)]));
  }, [instancesQ.data]);
  const agents = useMemo(() => agentsQ.data || [], [agentsQ.data]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return agents.filter((agent) => bucket(agent).includes(filter) && (!needle || [agent.name, agent.agentKey, agent.description, agent.runtimeKind, agent.status, ...(frameworks.get(agent.id) ?? []).map(item => item.label)].filter(Boolean).join(' ').toLowerCase().includes(needle)));
  }, [agents, filter, query, frameworks]);

  function openAgent(agent: AgentDefinition) {
    navigate(scope.scopedPath(agentDetailPath(agent)));
  }

  return (
    <Page className="max-w-[1440px]">
      <PageHeader
        title="Agents"
        description="Choose an Agent to manage its capabilities, runtime connections and recent work."
        actions={canCreate ? <Button onClick={() => navigate(scope.scopedPath('/agent-center/agents/new'))}><Plus className="h-4 w-4" />New agent</Button> : undefined}
      />

      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex max-w-full gap-1 overflow-x-auto rounded-xl bg-slate-100/80 p-1">
          {FILTERS.map((f) => {
            const active = filter === f.key;
            const count = agents.filter((a) => bucket(a).includes(f.key)).length;
            return (
              <button
                key={f.key}
                onClick={() => setFilter(f.key)}
                className={cn(
                  'inline-flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition',
                  active ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-800',
                )}
              >
                {f.label}
                <span className={cn('rounded-full px-1.5 py-0.5 text-[11px]', active ? 'bg-slate-100 text-slate-600' : 'bg-slate-200/70')}>
                  {count}
                </span>
              </button>
            );
          })}
        </div>
        <label className="relative w-full lg:w-80">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input className="pl-9 shadow-none" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search agents" />
        </label>
      </div>

      {agentsQ.isError && <p className="text-sm text-red-600">{String(agentsQ.error)}</p>}

      {agentsQ.isLoading ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : visible.length === 0 ? (
        <EmptyState
          title={query ? 'No matching agents' : 'No agents yet'}
          description={query ? 'Try another search or choose a different runtime filter.' : 'Create a Managed or Hosted Agent, or register an External Application through the SDK.'}
          action={canCreate ? <Button onClick={() => navigate(scope.scopedPath('/agent-center/agents/new'))}>Create agent</Button> : undefined}
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
                  {!!frameworks.get(a.id)?.length && <div className="flex flex-wrap gap-1">{frameworks.get(a.id)!.map(item => <Badge key={item.label} tone={item.online ? 'info' : 'warning'}>{item.label}{item.count > 1 ? ` · ${item.count} instances` : ''}{item.online ? '' : ' · offline'}</Badge>)}</div>}
                  {a.runtimeKind === 'external-application' && !frameworks.get(a.id)?.length && <p className="text-xs text-muted-foreground">{instancesQ.isError ? 'Framework information unavailable' : instancesQ.isLoading ? 'Loading framework…' : 'No registered instance'}</p>}
                  {a.workspaceId && (
                    <button
                      type="button"
                      className="self-start rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(scope.scopedPath(`/agent-center/workspaces/${encodeURIComponent(a.workspaceId!)}`));
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
