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

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { JsonViewer } from '@/components/JsonViewer';
import { Page, PageHeader } from '@/components/Page';
import { PressureGauge } from '@/components/PressureGauge';
import {
  fetchAgentMetrics,
  fetchAgentSubagents,
  fetchAgentWorkspaces,
  fetchDataPlanes,
  fetchManagedAgent,
  fetchRuntimeSessions,
  phaseTone,
  sessionDetailPath,
} from './api';

type TabId = 'definition' | 'instances' | 'sessions' | 'usage' | 'inventory';

const TABS: { id: TabId; label: string }[] = [
  { id: 'definition', label: 'Definition' },
  { id: 'instances', label: 'Instances' },
  { id: 'sessions', label: 'Sessions' },
  { id: 'usage', label: 'Usage' },
  { id: 'inventory', label: 'Inventory' },
];

function isHistory(phase?: string) {
  const p = (phase || '').toLowerCase();
  return p === 'archived' || p === 'terminated';
}

function isActiveOps(phase?: string) {
  const p = (phase || '').toLowerCase();
  return p === 'active' || p === 'idle' || p === 'compressing' || (!p && !isHistory(phase));
}

export default function OperateAgentDetailPage({ name }: { name: string }) {
  const [params, setParams] = useSearchParams();
  const namespace = params.get('namespace') || 'default';
  const tabParam = params.get('tab');
  const tab: TabId = TABS.some((t) => t.id === tabParam) ? (tabParam as TabId) : 'definition';

  function setTab(next: TabId) {
    const nextParams = new URLSearchParams(params);
    if (next === 'definition') nextParams.delete('tab');
    else nextParams.set('tab', next);
    setParams(nextParams, { replace: true });
  }

  const agent = useQuery({
    queryKey: ['v1-agent', name, namespace],
    queryFn: () => fetchManagedAgent(name, namespace),
  });
  const sessions = useQuery({
    queryKey: ['runtime-sessions', name],
    queryFn: () => fetchRuntimeSessions({ agent: name }),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const planes = useQuery({
    queryKey: ['dataplanes', name, namespace],
    queryFn: () => fetchDataPlanes(name, namespace),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const metrics = useQuery({
    queryKey: ['agent-metrics', name, namespace],
    queryFn: () =>
      fetchAgentMetrics({
        agent: name,
        namespace,
        since: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
      }),
    enabled: tab === 'usage',
    refetchIntervalInBackground: false,
  });
  const subagents = useQuery({
    queryKey: ['agent-subagents', name, namespace],
    queryFn: () => fetchAgentSubagents(name, namespace),
    enabled: tab === 'inventory',
    retry: false,
  });
  const workspaces = useQuery({
    queryKey: ['agent-workspaces', name, namespace],
    queryFn: () => fetchAgentWorkspaces(name, namespace),
    enabled: tab === 'inventory',
    retry: false,
  });

  const a = agent.data || {};
  const caps = (a.capabilities as string[]) || [];
  const contractLevel = Number(a.contractLevel || 0);
  const agentConfig = a.agentConfig ?? a.spec ?? a.config;

  const allSessions = sessions.data?.sessions || [];
  const { active, history } = useMemo(() => {
    const act: typeof allSessions = [];
    const hist: typeof allSessions = [];
    for (const s of allSessions) {
      if (isHistory(s.phase)) hist.push(s);
      else if (isActiveOps(s.phase)) act.push(s);
      else hist.push(s);
    }
    return { active: act, history: hist };
  }, [allSessions]);

  return (
    <Page>
      <div>
        <Link to="/operate/agents" className="text-sm text-muted-foreground hover:text-foreground">
          ← Agents
        </Link>
        <PageHeader
          className="mt-2"
          title={name}
          description={`${namespace} · ${(a.runtime as string) || 'runtime unknown'} · contract L${contractLevel || '?'}`}
        />
        <div className="mt-3 flex flex-wrap gap-2">
          {caps.map((c) => (
            <Badge key={c} tone="info">
              {c}
            </Badge>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap gap-1 border-b border-border pb-px">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            className={`rounded-t-lg px-4 py-2.5 text-sm ${
              tab === t.id
                ? 'border border-b-0 border-border bg-white font-medium text-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'definition' && (
        <Card>
          <CardHeader>
            <CardTitle>Definition</CardTitle>
            <CardDescription>
              Effective snapshot from data-plane /agentscope/info (builder + workspace merge)
            </CardDescription>
          </CardHeader>
          <CardContent>
            {agent.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : agentConfig ? (
              <JsonViewer value={agentConfig} className="max-h-[32rem]" />
            ) : (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">
                  No agentConfig on this agent (common in BYO registry mode). Showing registry summary:
                </p>
                <JsonViewer
                  value={{
                    name: a.name,
                    namespace: a.namespace,
                    type: a.type,
                    runtime: a.runtime,
                    framework: a.framework,
                    replicas: a.replicas,
                    contractLevel: a.contractLevel,
                    capabilities: a.capabilities,
                    activeSessions: a.activeSessions,
                    source: a.source,
                  }}
                  className="max-h-96"
                />
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {tab === 'instances' && (
        <Card>
          <CardHeader>
            <CardTitle>Instances</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {(planes.data?.dataplanes || []).length === 0 ? (
              <p className="text-sm text-muted-foreground">No registered instances.</p>
            ) : (
              (planes.data?.dataplanes || []).map((dp) => (
                <div key={dp.instanceId} className="rounded-lg border border-border px-4 py-3 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{dp.instanceId}</span>
                    <Badge tone={dp.healthy ? 'success' : 'danger'}>
                      {dp.healthy ? 'healthy' : 'stale'}
                    </Badge>
                  </div>
                  <div className="mt-1.5 text-sm text-muted-foreground">{dp.baseUrl}</div>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {(dp.capabilities || []).map((c) => (
                      <Badge key={c} tone="info">
                        {c}
                      </Badge>
                    ))}
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      )}

      {tab === 'sessions' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Active</CardTitle>
              <CardDescription>active · idle · compressing</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {active.length === 0 ? (
                <EmptyState title="No active sessions" description="Waiting for live sessions." className="py-10" />
              ) : (
                active.map((s) => (
                  <Link
                    key={s.id}
                    to={sessionDetailPath(s)}
                    className="flex items-center justify-between rounded-lg border border-border px-4 py-3 text-sm hover:bg-muted/50"
                  >
                    <div className="min-w-0">
                      <div className="truncate font-medium">{s.sessionId}</div>
                      <div className="mt-1">
                        <Badge tone={phaseTone(s.phase)}>{s.phase || '—'}</Badge>
                      </div>
                    </div>
                    <PressureGauge value={s.snapshot?.contextPressure} />
                  </Link>
                ))
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>History</CardTitle>
              <CardDescription>archived (restore OK) · terminated</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {history.length === 0 ? (
                <p className="text-sm text-muted-foreground">No archived sessions.</p>
              ) : (
                history.map((s) => (
                  <Link
                    key={s.id}
                    to={sessionDetailPath(s)}
                    className="flex items-center justify-between rounded-lg border border-border px-4 py-3 text-sm hover:bg-muted/50"
                  >
                    <div className="min-w-0">
                      <div className="truncate font-medium">{s.sessionId}</div>
                      <div className="mt-1">
                        <Badge tone={phaseTone(s.phase)}>{s.phase || '—'}</Badge>
                      </div>
                    </div>
                    <PressureGauge value={s.snapshot?.contextPressure} />
                  </Link>
                ))
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {tab === 'usage' && (
        <Card>
          <CardHeader>
            <CardTitle>Usage samples</CardTitle>
            <CardDescription>
              One row per control-plane poll (last 24h). Tokens = usage observed in that interval
              (delta), not a running total ·{' '}
              <Link className="text-primary hover:underline" to="/operate">
                Fleet overview
              </Link>
            </CardDescription>
          </CardHeader>
          <CardContent>
            {metrics.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : metrics.isError ? (
              <p className="text-sm text-muted-foreground">Metrics unavailable.</p>
            ) : (metrics.data?.metrics || []).length === 0 ? (
              <p className="text-sm text-muted-foreground">No metrics samples yet.</p>
            ) : (
              <div className="overflow-hidden rounded-lg border border-border">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                    <tr>
                      <th className="px-4 py-3 font-medium">Recorded</th>
                      <th className="px-4 py-3 font-medium">Active</th>
                      <th className="px-4 py-3 font-medium">Δ Tokens</th>
                      <th className="px-4 py-3 font-medium">Pressure</th>
                      <th className="px-4 py-3 font-medium">Errors</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {(metrics.data?.metrics || []).slice(0, 50).map((m) => (
                      <tr key={m.id}>
                        <td className="px-4 py-3 text-sm text-muted-foreground">
                          {new Date(m.recordedAt).toLocaleString()}
                        </td>
                        <td className="px-4 py-3 font-mono tabular-nums">{m.activeSessions}</td>
                        <td className="px-4 py-3 font-mono tabular-nums">{(m.totalTokens ?? 0).toLocaleString()}</td>
                        <td className="px-4 py-3">
                          <PressureGauge value={m.avgContextPressure} />
                        </td>
                        <td className="px-4 py-3 font-mono tabular-nums">{m.errorCount ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {tab === 'inventory' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Subagents</CardTitle>
              <CardDescription>GET /api/v1/agents/:name/subagents</CardDescription>
            </CardHeader>
            <CardContent>
              {subagents.isLoading ? (
                <p className="text-sm text-muted-foreground">Loading…</p>
              ) : !subagents.data ? (
                <p className="text-sm text-muted-foreground">
                  Subagent inventory unavailable (404/501 common in BYO mode).
                </p>
              ) : (
                <div className="space-y-3">
                  {subagents.data.instances.map((inst) => (
                    <div key={inst.instanceId} className="rounded-lg border border-border p-4 text-sm">
                      <div className="mb-2.5 text-sm text-muted-foreground">{inst.instanceId}</div>
                      {(inst.subagents || []).length === 0 ? (
                        <p className="text-muted-foreground">No subagents.</p>
                      ) : (
                        (inst.subagents || []).map((sa) => (
                          <div key={sa.name} className="border-t border-border py-2.5 first:border-0 first:pt-0">
                            <div className="font-medium">{sa.name}</div>
                            {sa.description && (
                              <div className="mt-0.5 text-sm text-muted-foreground">{sa.description}</div>
                            )}
                          </div>
                        ))
                      )}
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Workspaces</CardTitle>
              <CardDescription>GET /api/v1/agents/:name/workspaces</CardDescription>
            </CardHeader>
            <CardContent>
              {workspaces.isLoading ? (
                <p className="text-sm text-muted-foreground">Loading…</p>
              ) : !workspaces.data ? (
                <p className="text-sm text-muted-foreground">
                  Workspace inventory unavailable (404/501 common in BYO mode).
                </p>
              ) : (
                <div className="space-y-3">
                  {workspaces.data.instances.map((inst) => (
                    <div key={inst.instanceId} className="rounded-lg border border-border p-4 text-sm">
                      <div className="mb-2.5 text-sm text-muted-foreground">{inst.instanceId}</div>
                      {(inst.workspaces || []).length === 0 ? (
                        <p className="text-muted-foreground">No workspaces.</p>
                      ) : (
                        (inst.workspaces || []).map((ws) => (
                          <div key={ws.path} className="border-t border-border py-2.5 first:border-0 first:pt-0">
                            <div className="font-mono text-sm">{ws.path}</div>
                            <div className="mt-0.5 text-sm text-muted-foreground">
                              {ws.mode || 'mode n/a'}
                              {ws.sizeBytes != null ? ` · ${ws.sizeBytes.toLocaleString()} bytes` : ''}
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </Page>
  );
}
