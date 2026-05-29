import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import {
  listInstances, listAdminSessions,
  InstanceView, AdminSessionView,
} from '../../api/admin';

function timeAgo(ms: number): string {
  if (!ms) return '—';
  const d = Date.now() - ms;
  if (d < 60_000)    return `${Math.floor(d / 1000)}s ago`;
  if (d < 3_600_000) return `${Math.floor(d / 60_000)}m ago`;
  if (d < 86_400_000)return `${Math.floor(d / 3_600_000)}h ago`;
  return `${Math.floor(d / 86_400_000)}d ago`;
}

type SortKey = 'lastActivity' | 'agentId' | 'sessions';

const S: Record<string, React.CSSProperties> = {
  toolbar: { display: 'flex', gap: 12, alignItems: 'center', marginBottom: 20, flexWrap: 'wrap' as const },
  title:   { margin: 0, fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  input:   { background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 8, color: '#0f172a', fontSize: '0.9rem', padding: '8px 14px', outline: 'none' },
  refreshBtn: { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  sortBtnBase: { borderRadius: 6, padding: '5px 12px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 500 } as React.CSSProperties,
  table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.9rem', background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  th:    { textAlign: 'left' as const, padding: '12px 16px', background: '#f8fafc', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td:    { padding: '12px 16px', borderBottom: '1px solid #f1f5f9', color: '#334155', verticalAlign: 'middle' as const },
  mono:  { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#64748b' },
  agentPill: { display: 'inline-block', padding: '2px 10px', borderRadius: 999, fontSize: '0.8rem', background: '#dbeafe', color: '#1d4ed8', fontWeight: 500 },
  viewBtn: { background: '#eef2ff', border: '1px solid #c7d2fe', color: '#4338ca', borderRadius: 6, padding: '5px 12px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 500 },
  err: { color: '#dc2626', fontSize: '0.9rem', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '12px 16px', marginBottom: 16 },
  stat: { fontSize: '0.86rem', color: '#64748b', marginLeft: 'auto' },
  hint: { fontSize: '0.85rem', color: '#64748b', marginBottom: 18, lineHeight: 1.6 },
};

interface AgentRow {
  agentId: string;
  className: string;
  liveSessionCount: number;
  lastActivityMs: number;
}

export default function InstancesPage() {
  const navigate = useNavigate();
  const [instances, setInstances] = useState<InstanceView[]>([]);
  const [sessions,  setSessions]  = useState<AdminSessionView[]>([]);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState<string | null>(null);
  const [filterAgent, setFilterAgent] = useState('');
  const [sortKey,  setSortKey]  = useState<SortKey>('lastActivity');

  async function load() {
    setLoading(true); setError(null);
    try {
      const [inst, sess] = await Promise.all([listInstances(), listAdminSessions(500)]);
      setInstances(inst);
      setSessions(sess);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  // Join registered agents with live sessions
  const rows: AgentRow[] = useMemo(() => {
    const byAgent = new Map<string, AdminSessionView[]>();
    for (const s of sessions) {
      const arr = byAgent.get(s.agentId) ?? [];
      arr.push(s);
      byAgent.set(s.agentId, arr);
    }
    return instances.map(i => {
      const ss = byAgent.get(i.agentId) ?? [];
      const lastActivityMs = ss.reduce((m, s) => Math.max(m, s.lastActivityMs), 0);
      return {
        agentId: i.agentId,
        className: i.className,
        liveSessionCount: ss.length,
        lastActivityMs,
      };
    });
  }, [instances, sessions]);

  const displayed = useMemo(() => {
    let list = rows.filter(r => !filterAgent || r.agentId.toLowerCase().includes(filterAgent.toLowerCase()));
    list = [...list].sort((a, b) => {
      switch (sortKey) {
        case 'lastActivity': return b.lastActivityMs - a.lastActivityMs;
        case 'sessions':     return b.liveSessionCount - a.liveSessionCount;
        case 'agentId':      return a.agentId.localeCompare(b.agentId);
        default: return 0;
      }
    });
    return list;
  }, [rows, filterAgent, sortKey]);

  const totalLiveSessions = rows.reduce((sum, r) => sum + r.liveSessionCount, 0);

  return (
    <>
      <AdminPageLayout>
        {/* Toolbar */}
        <div style={S.toolbar}>
          <h2 style={S.title}>Registered Agents</h2>
          <button style={S.refreshBtn} onClick={load} disabled={loading}>
            {loading ? '…' : '↺ Refresh'}
          </button>

          <input
            style={S.input}
            placeholder="Filter by agent id…"
            value={filterAgent}
            onChange={e => setFilterAgent(e.target.value)}
          />

          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <span style={{ fontSize: '0.82rem', color: '#64748b', marginRight: 4, fontWeight: 500 }}>Sort:</span>
            {(['lastActivity', 'sessions', 'agentId'] as SortKey[]).map(k => (
              <button key={k} style={{ ...S.sortBtnBase, background: sortKey === k ? '#eef2ff' : '#ffffff', border: `1px solid ${sortKey === k ? '#c7d2fe' : '#d1d5db'}`, color: sortKey === k ? '#4338ca' : '#475569' }} onClick={() => setSortKey(k)}>
                {k === 'lastActivity' ? 'Recent' : k === 'sessions' ? 'Busiest' : 'Agent'}
              </button>
            ))}
          </div>

          <span style={S.stat}>
            {displayed.length} / {rows.length} agent{rows.length !== 1 ? 's' : ''} · {totalLiveSessions} live session{totalLiveSessions !== 1 ? 's' : ''}
          </span>
        </div>

        <div style={S.hint}>
          One row per agentId declared in <code style={{ background: '#f1f5f9', padding: '1px 6px', borderRadius: 4, fontSize: '0.82rem' }}>agentscope.json</code>. Live session counts and last-activity are joined from the active session registry.
        </div>

        {error && <div style={S.err}>{error}</div>}

        {/* Table */}
        <table style={S.table}>
          <thead>
            <tr>
              <th style={S.th}>Agent</th>
              <th style={S.th}>Class</th>
              <th style={S.th}>Live Sessions</th>
              <th style={S.th}>Last Active</th>
              <th style={S.th}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {displayed.length === 0 && !loading && (
              <tr>
                <td style={{ ...S.td, color: '#94a3b8' }} colSpan={5}>
                  {rows.length === 0 ? 'No registered agents.' : 'No agents match the current filter.'}
                </td>
              </tr>
            )}
            {displayed.map(r => (
              <tr key={r.agentId}>
                <td style={S.td}>
                  <span style={S.agentPill}>{r.agentId}</span>
                </td>
                <td style={{ ...S.td, ...S.mono }}>{r.className}</td>
                <td style={{ ...S.td, textAlign: 'center' as const, color: r.liveSessionCount > 0 ? '#4f46e5' : '#94a3b8', fontWeight: r.liveSessionCount > 0 ? 600 : 400 }}>
                  {r.liveSessionCount}
                </td>
                <td style={{ ...S.td, fontSize: '0.85rem' }}>{timeAgo(r.lastActivityMs)}</td>
                <td style={S.td}>
                  <button style={S.viewBtn} onClick={() => navigate('/admin/sessions')}>
                    Sessions
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </AdminPageLayout>
    </>
  );
}
