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
import { useI18n } from '@/i18n';
import { fetchManagedAgents, fetchRuntimeSessions, phaseTone, sessionDetailPath } from './api';
import { formatNumber, statusLabel } from './i18n';

const PAGE_SIZE = 50;
const PHASES = ['active', 'idle', 'compressing', 'archived', 'terminated'] as const;

function isPhase(v: string): v is (typeof PHASES)[number] {
  return (PHASES as readonly string[]).includes(v);
}

export default function OperateSessionsPage() {
  const { locale, t } = useI18n();
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
        title={t('operate.fields.sessions')}
        description={t('operate.sessions.description')}
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">{t('operate.fields.agent')}</span>
          <select
            className="h-10 min-w-[10rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={agent}
            onChange={(e) => updateAgent(e.target.value)}
          >
            <option value="">{t('operate.sessions.allAgents')}</option>
            {agentOptions.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>

        <label className="grid gap-1 text-sm">
          <span className="text-muted-foreground">{t('operate.fields.phase')}</span>
          <select
            className="h-10 min-w-[9rem] rounded-lg border border-border bg-white px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={phase}
            onChange={(e) => updatePhase(e.target.value)}
          >
            <option value="">{t('operate.sessions.allPhases')}</option>
            {PHASES.map((p) => (
              <option key={p} value={p}>
                {statusLabel(t, p)}
              </option>
            ))}
          </select>
        </label>

        <label className="grid min-w-[16rem] flex-1 gap-1 text-sm">
          <span className="text-muted-foreground">{t('operate.fields.search')}</span>
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t('operate.sessions.searchPlaceholder')}
          />
        </label>
      </div>

      {list.length === 0 && !sessions.isLoading ? (
        <EmptyState title={t('operate.sessions.empty')} description={t('operate.sessions.emptyDescription')} />
      ) : filtered.length === 0 && !sessions.isLoading ? (
        <EmptyState title={t('operate.common.noMatches')} description={t('operate.sessions.noMatches', { query: q.trim() })} />
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-5 py-3.5 font-medium">{t('operate.fields.agent')}</th>
                  <th className="px-5 py-3.5 font-medium">{t('operate.fields.session')}</th>
                  <th className="px-5 py-3.5 font-medium">{t('operate.fields.phase')}</th>
                  <th className="px-5 py-3.5 font-medium">{t('operate.fields.pressure')}</th>
                  <th className="px-5 py-3.5 font-medium">{t('operate.fields.messages')}</th>
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
                      <Badge tone={phaseTone(s.phase)}>{statusLabel(t, s.phase)}</Badge>
                    </td>
                    <td className="px-5 py-3.5">
                      <PressureGauge value={s.snapshot?.contextPressure} />
                    </td>
                    <td className="px-5 py-3.5 font-mono tabular-nums text-muted-foreground">
                      {s.snapshot?.effectiveMessageCount != null || s.snapshot?.messageCount != null
                        ? formatNumber(
                            locale,
                            s.snapshot?.effectiveMessageCount ?? s.snapshot?.messageCount ?? 0,
                          )
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
            <span>
              {t('operate.sessions.showingRange', {
                start: formatNumber(locale, offset + 1),
                end: formatNumber(locale, offset + list.length),
              })}
              {q.trim() ? ` · ${t('operate.sessions.matchCount', { count: formatNumber(locale, filtered.length) })}` : ''}
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={!canPrev || sessions.isFetching}
                onClick={() => setOffset((o) => Math.max(0, o - PAGE_SIZE))}
              >
                {t('operate.pagination.previous')}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!canNext || sessions.isFetching}
                onClick={() => setOffset((o) => o + PAGE_SIZE)}
              >
                {t('operate.pagination.next')}
              </Button>
            </div>
          </div>
        </>
      )}
    </Page>
  );
}
