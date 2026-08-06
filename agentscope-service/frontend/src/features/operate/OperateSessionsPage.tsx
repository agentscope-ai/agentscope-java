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
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/EmptyState';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { PressureGauge } from '@/components/PressureGauge';
import { fetchManagedAgents, fetchRuntimeSessions, phaseTone, sessionDetailPath } from './api';

const PAGE_SIZE = 50;
const PHASES = ['active', 'idle', 'compressing', 'archived', 'terminated'] as const;

function isPhase(v: string): v is (typeof PHASES)[number] {
  return (PHASES as readonly string[]).includes(v);
}

export default function OperateSessionsPage() {
  const [params, setParams] = useSearchParams();
  const [agent, setAgent] = useState(() => params.get('agent') || '');
  const [phase, setPhase] = useState(() => {
    const p = params.get('phase') || '';
    return isPhase(p) ? p : '';
  });
  const [q, setQ] = useState('');
  const [offset, setOffset] = useState(0);

  useEffect(() => {
    const nextPhase = params.get('phase') || '';
    const nextAgent = params.get('agent') || '';
    setPhase(isPhase(nextPhase) ? nextPhase : '');
    setAgent(nextAgent);
    setOffset(0);
  }, [params]);

  function updatePhase(next: string) {
    const nextParams = new URLSearchParams(params);
    if (next) nextParams.set('phase', next);
    else nextParams.delete('phase');
    setParams(nextParams, { replace: true });
  }

  function updateAgent(next: string) {
    const nextParams = new URLSearchParams(params);
    if (next) nextParams.set('agent', next);
    else nextParams.delete('agent');
    setParams(nextParams, { replace: true });
  }

  const agents = useQuery({
    queryKey: ['v1-agents', 'all'],
    queryFn: () => fetchManagedAgents({ presence: 'all' }),
    refetchInterval: 30_000,
  });

  const sessions = useQuery({
    queryKey: ['runtime-sessions', agent, phase, offset],
    queryFn: () =>
      fetchRuntimeSessions({
        agent: agent || undefined,
        phase: phase || undefined,
        limit: PAGE_SIZE,
        offset,
      }),
    refetchInterval: 10_000,
  });

  const list = sessions.data?.sessions || [];
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return list;
    return list.filter((s) => {
      const hay = [s.sessionId, s.id, s.agentName, s.namespace, s.phase, s.framework]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return hay.includes(needle);
    });
  }, [list, q]);

  const agentOptions = (agents.data?.items || [])
    .map((a) => a.name)
    .filter(Boolean)
    .sort();

  const canPrev = offset > 0;
  const canNext = list.length >= PAGE_SIZE;

  return (
    <Page>
      <PageHeader
        title="Sessions"
        description="Runtime sessions across all managed data planes."
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">Agent</span>
          <select
            className="h-10 min-w-[10rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={agent}
            onChange={(e) => updateAgent(e.target.value)}
          >
            <option value="">All agents</option>
            {agentOptions.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>

        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">Phase</span>
          <select
            className="h-10 min-w-[9rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={phase}
            onChange={(e) => updatePhase(e.target.value)}
          >
            <option value="">All phases</option>
            {PHASES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>

        <label className="grid min-w-[16rem] flex-1 gap-1 text-sm">
          <span className="text-muted-foreground">Search</span>
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter this page by session id, agent…"
          />
        </label>
      </div>

      {list.length === 0 && !sessions.isLoading ? (
        <EmptyState title="No sessions" description="No sessions match the current filters." />
      ) : filtered.length === 0 && !sessions.isLoading ? (
        <EmptyState title="No matches" description={`No sessions on this page match “${q.trim()}”.`} />
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-5 py-3.5 font-medium">Agent</th>
                  <th className="px-5 py-3.5 font-medium">Session</th>
                  <th className="px-5 py-3.5 font-medium">Phase</th>
                  <th className="px-5 py-3.5 font-medium">Pressure</th>
                  <th className="px-5 py-3.5 font-medium">Messages</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filtered.map((s) => (
                  <tr key={s.id || `${s.agentName}/${s.sessionId}`} className="hover:bg-muted/40">
                    <td className="px-5 py-3.5 font-medium">{s.agentName}</td>
                    <td className="px-5 py-3.5">
                      <Link className="text-primary hover:underline" to={sessionDetailPath(s)}>
                        {s.sessionId}
                      </Link>
                    </td>
                    <td className="px-5 py-3.5">
                      <Badge tone={phaseTone(s.phase)}>{s.phase}</Badge>
                    </td>
                    <td className="px-5 py-3.5">
                      <PressureGauge value={s.snapshot?.contextPressure} />
                    </td>
                    <td className="px-5 py-3.5 font-mono tabular-nums text-muted-foreground">
                      {s.snapshot?.effectiveMessageCount ?? s.snapshot?.messageCount ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
            <span>
              Showing {offset + 1}–{offset + list.length}
              {q.trim() ? ` · ${filtered.length} match search` : ''}
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={!canPrev || sessions.isFetching}
                onClick={() => setOffset((o) => Math.max(0, o - PAGE_SIZE))}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!canNext || sessions.isFetching}
                onClick={() => setOffset((o) => o + PAGE_SIZE)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </Page>
  );
}
