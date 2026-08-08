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

import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { AgentPresence, fetchManagedAgents } from './api';

function parsePresence(v: string | null, healthLegacy: string | null): AgentPresence {
  if (v === 'live' || v === 'offline' || v === 'historical' || v === 'all') return v;
  // Back-compat with old ?health= filter.
  if (healthLegacy === 'stale') return 'offline';
  if (healthLegacy === 'healthy') return 'live';
  return 'live';
}

function presenceLabel(p: AgentPresence): string {
  switch (p) {
    case 'live':
      return 'Live';
    case 'offline':
      return 'Offline';
    case 'historical':
      return 'Historical';
    case 'all':
      return 'All';
  }
}

function presenceTone(p?: string): 'success' | 'warning' | 'default' | 'info' {
  switch (p) {
    case 'live':
      return 'success';
    case 'offline':
      return 'warning';
    case 'historical':
      return 'default';
    default:
      return 'info';
  }
}

export default function OperateAgentsPage() {
  const [params, setParams] = useSearchParams();
  const [presence, setPresence] = useState<AgentPresence>(() =>
    parsePresence(params.get('presence'), params.get('health')),
  );
  const [q, setQ] = useState('');

  const agents = useQuery({
    queryKey: ['v1-agents', presence],
    queryFn: () => fetchManagedAgents({ presence }),
    refetchInterval: 10_000,
  });

  useEffect(() => {
    setPresence(parsePresence(params.get('presence'), params.get('health')));
  }, [params]);

  function updatePresence(next: AgentPresence) {
    const nextParams = new URLSearchParams(params);
    nextParams.delete('health');
    if (next === 'live') nextParams.delete('presence');
    else nextParams.set('presence', next);
    setParams(nextParams, { replace: true });
  }

  const items = agents.data?.items || [];

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return items;
    return items.filter((a) => {
      const hay = [a.name, a.displayName, a.namespace, a.runtime, a.type, a.presence]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return hay.includes(needle);
    });
  }, [items, q]);

  const emptyDescription = (() => {
    switch (presence) {
      case 'live':
        return 'Agents with at least one healthy data-plane instance appear here. Offline and session-only history are under other Presence filters.';
      case 'offline':
        return 'Agents that still have registry entries but no healthy instance (missed heartbeats).';
      case 'historical':
        return 'Agents seen only in past runtime sessions, with no registered instance.';
      case 'all':
        return 'No agents in any presence bucket yet. Register a data plane via POST /api/v1/dataplanes/register.';
    }
  })();

  return (
    <Page>
      <PageHeader
        title="Agents"
        description="Live data planes by default. Use Presence to inspect offline or historical agents."
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">Presence</span>
          <select
            className="h-10 min-w-[12rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={presence}
            onChange={(e) => updatePresence(e.target.value as AgentPresence)}
          >
            <option value="live">Live</option>
            <option value="offline">Offline</option>
            <option value="historical">Historical</option>
            <option value="all">All</option>
          </select>
        </label>

        {(items.length > 0 || q) && (
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
          title={`No ${presenceLabel(presence).toLowerCase()} agents`}
          description={emptyDescription}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          title="No matches"
          description={`No ${presenceLabel(presence).toLowerCase()} agents matching “${q.trim()}”.`}
        />
      ) : (
        <div className="grid gap-5 md:grid-cols-2">
          {filtered.map((a) => (
            <Link
              key={`${a.namespace}/${a.name}`}
              to={`/operate/agents/${encodeURIComponent(a.name)}?namespace=${encodeURIComponent(a.namespace || 'default')}${a.presence === 'offline' ? '&tab=instances' : ''}`}
            >
              <Card className="h-full transition hover:border-indigo-200 hover:shadow-md">
                <CardHeader>
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle>{a.displayName || a.name}</CardTitle>
                    <div className="flex shrink-0 gap-1">
                      {a.presence && (
                        <Badge tone={presenceTone(a.presence)}>{a.presence}</Badge>
                      )}
                      <Badge tone="info">{a.replicas || '—'}</Badge>
                    </div>
                  </div>
                  <CardDescription>
                    {a.namespace} · {a.runtime || a.type || 'unknown'}
                  </CardDescription>
                </CardHeader>
                <CardContent className="text-sm text-muted-foreground">
                  Active sessions: {a.activeSessions ?? 0}
                  {typeof a.healthyCount === 'number' && (
                    <> · healthy {a.healthyCount}/{a.instanceCount ?? 0}</>
                  )}
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </Page>
  );
}
