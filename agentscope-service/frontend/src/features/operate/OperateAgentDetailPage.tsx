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
import { type TranslationKey, useI18n } from '@/i18n';
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
import { formatDateTime, formatNumber, statusLabel } from './i18n';

type TabId = 'definition' | 'instances' | 'sessions' | 'usage' | 'inventory';

const TABS: { id: TabId; labelKey: TranslationKey }[] = [
  { id: 'definition', labelKey: 'operate.agentDetail.tabs.definition' },
  { id: 'instances', labelKey: 'operate.agentDetail.tabs.instances' },
  { id: 'sessions', labelKey: 'operate.agentDetail.tabs.sessions' },
  { id: 'usage', labelKey: 'operate.agentDetail.tabs.usage' },
  { id: 'inventory', labelKey: 'operate.agentDetail.tabs.inventory' },
];

function last24HoursSince(): string {
  return new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
}

function isHistory(phase?: string) {
  const p = (phase || '').toLowerCase();
  return p === 'archived' || p === 'terminated';
}

function isActiveOps(phase?: string) {
  const p = (phase || '').toLowerCase();
  return p === 'active' || p === 'idle' || p === 'compressing' || (!p && !isHistory(phase));
}

export default function OperateAgentDetailPage({ name }: { name: string }) {
  const { locale, t } = useI18n();
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
        since: last24HoursSince(),
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
          ← {t('operate.fields.agents')}
        </Link>
        <PageHeader
          className="mt-2"
          title={name}
          description={t('operate.agentDetail.subtitle', {
            namespace,
            runtime: (a.runtime as string) || t('operate.agentDetail.runtimeUnknown'),
            contract: contractLevel || '?',
          })}
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
        {TABS.map((tabItem) => (
          <button
            key={tabItem.id}
            type="button"
            onClick={() => setTab(tabItem.id)}
            className={`rounded-t-lg px-4 py-2.5 text-sm ${
              tab === tabItem.id
                ? 'border border-b-0 border-border bg-white font-medium text-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {t(tabItem.labelKey)}
          </button>
        ))}
      </div>

      {tab === 'definition' && (
        <Card>
          <CardHeader>
            <CardTitle>{t('operate.agentDetail.definition.title')}</CardTitle>
            <CardDescription>
              {t('operate.agentDetail.definition.description')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {agent.isLoading ? (
              <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
            ) : agentConfig ? (
              <JsonViewer value={agentConfig} className="max-h-[32rem]" />
            ) : (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">
                  {t('operate.agentDetail.definition.noConfig')}
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
            <CardTitle>{t('operate.agentDetail.instances.title')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {(planes.data?.dataplanes || []).length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('operate.agentDetail.instances.empty')}</p>
            ) : (
              (planes.data?.dataplanes || []).map((dp) => (
                <div key={dp.instanceId} className="rounded-lg border border-border px-4 py-3 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{dp.instanceId}</span>
                    <Badge tone={dp.healthy ? 'success' : 'danger'}>
                      {dp.healthy ? t('status.healthy') : t('operate.status.stale')}
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
              <CardTitle>{t('status.active')}</CardTitle>
              <CardDescription>{t('operate.agentDetail.sessions.activePhases')}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {active.length === 0 ? (
                <EmptyState title={t('operate.agentDetail.sessions.noActive')} description={t('operate.agentDetail.sessions.waiting')} className="py-10" />
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
                        <Badge tone={phaseTone(s.phase)}>{statusLabel(t, s.phase)}</Badge>
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
              <CardTitle>{t('operate.agentDetail.sessions.history')}</CardTitle>
              <CardDescription>{t('operate.agentDetail.sessions.historyPhases')}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {history.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('operate.agentDetail.sessions.noArchived')}</p>
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
                        <Badge tone={phaseTone(s.phase)}>{statusLabel(t, s.phase)}</Badge>
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
            <CardTitle>{t('operate.agentDetail.usage.title')}</CardTitle>
            <CardDescription>
              {t('operate.agentDetail.usage.description')}{' · '}
              <Link className="text-primary hover:underline" to="/operate">
                {t('operate.overview.title')}
              </Link>
            </CardDescription>
          </CardHeader>
          <CardContent>
            {metrics.isLoading ? (
              <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
            ) : metrics.isError ? (
              <p className="text-sm text-muted-foreground">{t('operate.agentDetail.usage.unavailable')}</p>
            ) : (metrics.data?.metrics || []).length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('operate.agentDetail.usage.empty')}</p>
            ) : (
              <div className="overflow-hidden rounded-lg border border-border">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                    <tr>
                      <th className="px-4 py-3 font-medium">{t('operate.fields.recorded')}</th>
                      <th className="px-4 py-3 font-medium">{t('status.active')}</th>
                      <th className="px-4 py-3 font-medium">{t('operate.fields.deltaTokens')}</th>
                      <th className="px-4 py-3 font-medium">{t('operate.fields.pressure')}</th>
                      <th className="px-4 py-3 font-medium">{t('operate.fields.errors')}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {(metrics.data?.metrics || []).slice(0, 50).map((m) => (
                      <tr key={m.id}>
                        <td className="px-4 py-3 text-sm text-muted-foreground">
                          {formatDateTime(locale, m.recordedAt)}
                        </td>
                        <td className="px-4 py-3 font-mono tabular-nums">
                          {formatNumber(locale, m.activeSessions)}
                        </td>
                        <td className="px-4 py-3 font-mono tabular-nums">{formatNumber(locale, m.totalTokens ?? 0)}</td>
                        <td className="px-4 py-3">
                          <PressureGauge value={m.avgContextPressure} />
                        </td>
                        <td className="px-4 py-3 font-mono tabular-nums">
                          {formatNumber(locale, m.errorCount ?? 0)}
                        </td>
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
              <CardTitle>{t('operate.agentDetail.inventory.subagents')}</CardTitle>
              <CardDescription>GET /api/v1/agents/:name/subagents</CardDescription>
            </CardHeader>
            <CardContent>
              {subagents.isLoading ? (
                <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
              ) : !subagents.data ? (
                <p className="text-sm text-muted-foreground">
                  {t('operate.agentDetail.inventory.subagentsUnavailable')}
                </p>
              ) : (
                <div className="space-y-3">
                  {subagents.data.instances.map((inst) => (
                    <div key={inst.instanceId} className="rounded-lg border border-border p-4 text-sm">
                      <div className="mb-2.5 text-sm text-muted-foreground">{inst.instanceId}</div>
                      {(inst.subagents || []).length === 0 ? (
                        <p className="text-muted-foreground">{t('operate.agentDetail.inventory.noSubagents')}</p>
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
              <CardTitle>{t('operate.agentDetail.inventory.workspaces')}</CardTitle>
              <CardDescription>GET /api/v1/agents/:name/workspaces</CardDescription>
            </CardHeader>
            <CardContent>
              {workspaces.isLoading ? (
                <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
              ) : !workspaces.data ? (
                <p className="text-sm text-muted-foreground">
                  {t('operate.agentDetail.inventory.workspacesUnavailable')}
                </p>
              ) : (
                <div className="space-y-3">
                  {workspaces.data.instances.map((inst) => (
                    <div key={inst.instanceId} className="rounded-lg border border-border p-4 text-sm">
                      <div className="mb-2.5 text-sm text-muted-foreground">{inst.instanceId}</div>
                      {(inst.workspaces || []).length === 0 ? (
                        <p className="text-muted-foreground">{t('operate.agentDetail.inventory.noWorkspaces')}</p>
                      ) : (
                        (inst.workspaces || []).map((ws) => (
                          <div key={ws.path} className="border-t border-border py-2.5 first:border-0 first:pt-0">
                            <div className="font-mono text-sm">{ws.path}</div>
                            <div className="mt-0.5 text-sm text-muted-foreground">
                              {ws.mode || t('operate.agentDetail.inventory.modeUnavailable')}
                              {ws.sizeBytes != null
                                ? ` · ${t('operate.bytes', { count: formatNumber(locale, ws.sizeBytes) })}`
                                : ''}
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
