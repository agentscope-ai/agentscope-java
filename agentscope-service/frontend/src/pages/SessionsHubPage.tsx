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

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  ManagedSession,
  ManagedSessionListStatus,
  archiveManagedSession,
  deleteManagedSession,
  isTeamOriginatedSession,
  listManagedSessions,
  parseTeamExternalKey,
  restoreManagedSession,
  teamDetailPath,
} from '../api/managedSessions';
import { AgentDefinition, listAgents } from '../api/agents';
import { Environment, listEnvironments } from '../api/environments';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1000 },
  header: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 18, flexWrap: 'wrap' },
  title: { margin: 0, fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em', flex: 1 },
  tabs: { display: 'flex', gap: 6, marginBottom: 16 },
  tab: {
    padding: '6px 12px', borderRadius: 8, border: '1px solid #e2e8f0',
    background: '#ffffff', color: '#64748b', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  },
  tabActive: { background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' },
  filter: {
    padding: '8px 12px', borderRadius: 8, border: '1px solid #cbd5e1',
    fontSize: '0.88rem', background: '#ffffff', color: '#334155', minWidth: 200,
  },
  primary: {
    padding: '8px 14px', borderRadius: 8, cursor: 'pointer', fontSize: '0.88rem', fontWeight: 600,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
    textDecoration: 'none', display: 'inline-flex', alignItems: 'center',
  },
  empty: { padding: '60px 0', color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  emptyLink: {
    color: '#6366f1', fontWeight: 600, textDecoration: 'none', fontSize: '0.95rem',
  },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '18px 20px', marginBottom: 12,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardHeader: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 },
  label: { fontSize: '0.98rem', color: '#0f172a', fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  time: { fontSize: '0.8rem', color: '#94a3b8', flexShrink: 0 },
  statusTag: {
    fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600,
    padding: '2px 8px', borderRadius: 6, flexShrink: 0,
  },
  teamTag: {
    fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600,
    padding: '2px 8px', borderRadius: 6, flexShrink: 0,
    color: '#0f766e', background: '#ccfbf1',
  },
  stopReason: {
    fontSize: '0.78rem', color: '#64748b', marginTop: 4,
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  mounts: { fontSize: '0.78rem', color: '#64748b', marginTop: 8 },
  agent: { fontSize: '0.82rem', color: '#4338ca', fontWeight: 500, marginTop: 6 },
  teamMeta: { fontSize: '0.78rem', color: '#0f766e', marginTop: 6 },
  cardFooter: {
    display: 'flex', alignItems: 'center', gap: 10, marginTop: 12,
    fontSize: '0.78rem', color: '#94a3b8', flexWrap: 'wrap',
  },
  action: {
    color: '#6366f1', cursor: 'pointer', fontWeight: 500, background: 'none',
    border: 'none', padding: 0, fontSize: '0.78rem',
  },
  danger: { color: '#dc2626' },
  err: { color: '#dc2626', fontSize: '0.9rem', marginBottom: 12 },
};

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

function statusStyle(status: string): React.CSSProperties {
  const base = { ...S.statusTag };
  switch (status) {
    case 'running':
      return { ...base, color: '#0369a1', background: '#e0f2fe' };
    case 'idle':
    case 'active':
      return { ...base, color: '#15803d', background: '#dcfce7' };
    case 'requires_action':
      return { ...base, color: '#c2410c', background: '#ffedd5' };
    case 'terminated':
    case 'archived':
      return { ...base, color: '#64748b', background: '#f1f5f9' };
    case 'rescheduled':
      return { ...base, color: '#a16207', background: '#fef9c3' };
    default:
      return { ...base, color: '#4338ca', background: '#eef2ff' };
  }
}

function stopReasonSummary(stopReason: Record<string, unknown> | null | undefined): string | null {
  if (!stopReason || typeof stopReason !== 'object') return null;
  const type = stopReason.type;
  if (typeof type === 'string' && type) return type;
  try {
    const raw = JSON.stringify(stopReason);
    return raw.length > 80 ? `${raw.slice(0, 77)}…` : raw;
  } catch {
    return null;
  }
}

function mountSummary(s: ManagedSession, envNameById: Map<string, string>): string {
  const env = envNameById.get(s.environmentId) || s.environmentId || '—';
  const vaults = s.vaultIds?.length ?? 0;
  const mems = s.memoryStoreIds?.length ?? 0;
  return `env: ${env} · vaults: ${vaults} · memory: ${mems}`;
}

/**
 * Top-level Managed Sessions hub (`/sessions`). Optional `?agentId=` filter.
 */
export default function SessionsHubPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const agentFilter = searchParams.get('agentId') ?? '';
  const [tab, setTab] = useState<ManagedSessionListStatus>('active');
  const [managedEntries, setManagedEntries] = useState<ManagedSession[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [envNameById, setEnvNameById] = useState<Map<string, string>>(new Map());
  const [err, setErr] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const navigate = useNavigate();

  const agentNameById = useMemo(
    () => new Map(agents.map(a => [a.id, a.name])),
    [agents],
  );

  const newSessionHref = agentFilter
    ? `/sessions/new?agentId=${encodeURIComponent(agentFilter)}`
    : '/sessions/new';

  const reload = useCallback(async () => {
    setErr(null);
    try {
      const [list, envs, agentList] = await Promise.all([
        listManagedSessions(agentFilter || undefined, tab),
        listEnvironments().catch(() => [] as Environment[]),
        listAgents().catch(() => [] as AgentDefinition[]),
      ]);
      setManagedEntries(list);
      setEnvNameById(new Map(envs.map(e => [e.id, e.name])));
      setAgents(agentList);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load sessions');
    }
  }, [agentFilter, tab]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function runAction(sessionId: string, action: () => Promise<unknown>) {
    setBusyId(sessionId);
    setErr(null);
    try {
      await action();
      await reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h2 style={S.title}>Sessions</h2>
        <select
          style={S.filter}
          value={agentFilter}
          onChange={e => {
            const next = new URLSearchParams(searchParams);
            if (e.target.value) next.set('agentId', e.target.value);
            else next.delete('agentId');
            setSearchParams(next, { replace: true });
          }}
        >
          <option value="">All agents</option>
          {agents.map(a => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </select>
        <Link to={newSessionHref} style={S.primary}>New session</Link>
      </div>

      <div style={S.tabs}>
        {([
          ['active', 'Active'],
          ['archived', 'Archived'],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            type="button"
            style={{ ...S.tab, ...(tab === key ? S.tabActive : {}) }}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {err && <div style={S.err}>{err}</div>}

      {!err && managedEntries.length === 0 && (
        <div style={S.empty}>
          {tab === 'archived' ? (
            'No archived sessions.'
          ) : (
            <>
              No managed sessions yet —{' '}
              <Link to={newSessionHref} style={S.emptyLink}>create a new session</Link>.
            </>
          )}
        </div>
      )}

      {managedEntries.map(s => {
        const reason = stopReasonSummary(s.stopReason);
        const archived = !!s.archivedAt;
        const agentLabel = agentNameById.get(s.agentId) || s.agentId;
        const teamRef = parseTeamExternalKey(s.externalKey);
        const fromTeam = isTeamOriginatedSession(s);
        return (
          <div key={s.id} style={S.card}>
            <div style={S.cardHeader}>
              <span style={S.label}>{s.id}</span>
              {fromTeam && <span style={S.teamTag} title={s.externalKey || undefined}>Team</span>}
              <span style={statusStyle(s.status)}>{s.status}</span>
              <span style={S.time}>{relTime(s.updatedAt)}</span>
            </div>
            <div style={S.agent}>{agentLabel}</div>
            {teamRef && (
              <div style={S.teamMeta}>
                from team {teamRef.namespace}/{teamRef.teamName} · member {teamRef.memberName}
              </div>
            )}
            {reason && <div style={S.stopReason}>stop: {reason}</div>}
            <div style={S.mounts}>{mountSummary(s, envNameById)}</div>
            <div style={S.cardFooter}>
              {!archived && (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => navigate(`/sessions/${encodeURIComponent(s.id)}`)}
                >
                  {fromTeam ? 'View transcript' : 'Open chat'}
                </button>
              )}
              {teamRef && (
                <button
                  type="button"
                  style={S.action}
                  onClick={() => navigate(teamDetailPath(teamRef))}
                >
                  Open team
                </button>
              )}
              <button
                type="button"
                style={S.action}
                onClick={() => navigate(`/sessions/${encodeURIComponent(s.id)}?tab=details`)}
              >
                Details
              </button>
              {!archived ? (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => {
                    if (!confirm('Archive this managed session?')) return;
                    void runAction(s.id, () => archiveManagedSession(s.id));
                  }}
                >
                  Archive
                </button>
              ) : (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => void runAction(s.id, () => restoreManagedSession(s.id))}
                >
                  Restore
                </button>
              )}
              <button
                type="button"
                style={{ ...S.action, ...S.danger }}
                disabled={busyId === s.id}
                onClick={() => {
                  if (!confirm('Delete this session entirely?')) return;
                  void runAction(s.id, () => deleteManagedSession(s.id));
                }}
              >
                Delete
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
