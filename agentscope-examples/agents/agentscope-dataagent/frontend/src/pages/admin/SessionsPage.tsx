import React, { useEffect, useState } from 'react';
import AdminPageLayout from '../../components/admin/AdminPageLayout';

// ── types & fetching ──────────────────────────────────────────────────
interface SessionView {
  sessionKey: string;
  agentId: string;
  userId: string | null;
  kind: string;
  lastActivityMs: number;
  idleMs: number;
}

interface SessionDetail {
  sessionKey: string;
  agentId: string;
  sessionId: string;
  label: string | null;
  kind: string;
  spawnedBy: string | null;
  spawnDepth: number;
  userId: string | null;
  gateKey: string | null;
  sessionFilePath: string | null;
  createdAtMs: number;
  lastActivityMs: number;
  idleMs: number;
}

interface SessionTreeNode {
  session: SessionDetail;
  children: SessionTreeNode[];
}

function authH(): Record<string, string> {
  const t = localStorage.getItem('claw_token') ?? '';
  return { Authorization: `Bearer ${t}` };
}

async function listAdminSessions(limit = 200): Promise<SessionView[]> {
  const r = await fetch(`/api/admin/sessions?limit=${limit}`, { headers: authH() });
  if (!r.ok) throw new Error('Failed to load sessions');
  return r.json();
}

async function fetchSessionTree(key: string): Promise<SessionTreeNode> {
  const r = await fetch(`/api/admin/sessions/${encodeURIComponent(key)}/tree`, { headers: authH() });
  if (!r.ok) throw new Error('Failed to load session tree');
  return r.json();
}

interface MutationEntry {
  ts: number;
  sessionKey: string | null;
  agentId: string | null;
  sessionId: string | null;
  toolCallId: string | null;
  toolName: string | null;
  path: string | null;
  kind: string | null;
  preHash: string | null;
  postHash: string | null;
  preSize: number;
  postSize: number;
}

async function fetchWorkspaceEvents(key: string, limit = 500): Promise<MutationEntry[]> {
  const r = await fetch(
    `/api/admin/sessions/${encodeURIComponent(key)}/workspace/events?limit=${limit}`,
    { headers: authH() },
  );
  if (!r.ok) throw new Error('Failed to load workspace events');
  return r.json();
}

function formatBytes(n: number): string {
  if (!n || n <= 0) return '–';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function kindBadgeStyle(kind: string | null): React.CSSProperties {
  const base: React.CSSProperties = {
    display: 'inline-block', padding: '2px 8px', borderRadius: 6,
    fontSize: '0.78rem', fontWeight: 600,
  };
  if (kind === 'CREATE') return { ...base, background: '#dcfce7', color: '#15803d', border: '1px solid #86efac' };
  if (kind === 'EDIT')   return { ...base, background: '#eef2ff', color: '#4338ca', border: '1px solid #c7d2fe' };
  if (kind === 'DELETE') return { ...base, background: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca' };
  return { ...base, background: '#f1f5f9', color: '#475569', border: '1px solid #e5e7eb' };
}

// ── helpers ───────────────────────────────────────────────────────────
function timeAgo(ms: number): string {
  if (!ms) return '—';
  const s = Math.floor((Date.now() - ms) / 1000);
  if (s < 60)    return 'just now';
  if (s < 3600)  return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return new Date(ms).toLocaleDateString();
}

// ── tree renderer ─────────────────────────────────────────────────────
function TreeRow({ node, depth }: { node: SessionTreeNode; depth: number }) {
  const s = node.session;
  return (
    <>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '10px 0', borderBottom: '1px solid #f1f5f9',
        fontSize: '0.88rem',
      }}>
        <span style={{ width: depth * 20, flexShrink: 0 }} />
        {depth > 0 && <span style={{ color: '#cbd5e1' }}>└─</span>}
        <span style={{
          background: '#dbeafe', color: '#1d4ed8', borderRadius: 999,
          padding: '2px 10px', fontSize: '0.82rem', flexShrink: 0, fontWeight: 500,
        }}>{s.agentId}</span>
        <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', color: '#475569', fontSize: '0.82rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={s.sessionKey}>
          {s.sessionKey.slice(0, 40)}…
        </span>
        <span style={{ color: '#94a3b8', marginLeft: 'auto', flexShrink: 0, fontSize: '0.82rem' }}>{timeAgo(s.lastActivityMs)}</span>
      </div>
      {node.children.map(c => <TreeRow key={c.session.sessionKey} node={c} depth={depth + 1} />)}
    </>
  );
}

// ── styles ────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  page:    { maxWidth: 1200 },
  toolbar: { display: 'flex', gap: 12, alignItems: 'center', marginBottom: 22, flexWrap: 'wrap' as const },
  title:   { margin: 0, fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  input:   { background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 8, color: '#0f172a', fontSize: '0.9rem', padding: '8px 14px', outline: 'none' },
  refreshBtn: { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.9rem', background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  th: { textAlign: 'left' as const, padding: '12px 16px', background: '#f8fafc', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td: { padding: '12px 16px', borderBottom: '1px solid #f1f5f9', color: '#334155', verticalAlign: 'top' as const },
  mono: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#64748b' },
  viewBtn: { background: '#eef2ff', border: '1px solid #c7d2fe', color: '#4338ca', borderRadius: 6, padding: '5px 12px', cursor: 'pointer', fontSize: '0.84rem', fontWeight: 500 },
  kindBadgeBase: { display: 'inline-block', padding: '2px 9px', borderRadius: 999, fontSize: '0.78rem', fontWeight: 500 } as React.CSSProperties,
  modal: { position: 'fixed' as const, inset: 0, background: 'rgba(15,23,42,0.4)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: '1rem' },
  modalBox: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, width: '85vw', maxWidth: 1200, maxHeight: '88vh', display: 'flex', flexDirection: 'column' as const, boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)' },
  modalHeader: { padding: '16px 22px', background: '#f8fafc', borderBottom: '1px solid #e5e7eb', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderRadius: '14px 14px 0 0' },
  closeBtn: { background: 'transparent', border: 'none', color: '#64748b', cursor: 'pointer', fontSize: '1.2rem' },
  modalBody: { flex: 1, overflowY: 'auto' as const, padding: '1.5rem' },
  detailGrid: {
    display: 'grid', gridTemplateColumns: '140px 1fr', rowGap: 10, columnGap: 16,
    fontSize: '0.88rem', marginBottom: 22,
  },
  detailKey: { color: '#94a3b8', fontWeight: 500, textTransform: 'uppercase' as const, letterSpacing: '0.04em', fontSize: '0.78rem' },
  detailVal: { color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.84rem', wordBreak: 'break-all' as const },
};

// ── component ─────────────────────────────────────────────────────────
export default function AdminSessionsPage() {
  const [sessions, setSessions] = useState<SessionView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filterUser, setFilterUser] = useState('');
  const [filterAgent, setFilterAgent] = useState('');

  const [viewKey, setViewKey] = useState<string | null>(null);
  const [tree, setTree] = useState<SessionTreeNode | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [modalTab, setModalTab] = useState<'tree' | 'workspace'>('tree');
  const [events, setEvents] = useState<MutationEntry[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);
  const [eventsError, setEventsError] = useState<string | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      setSessions(await listAdminSessions());
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  async function openTree(key: string) {
    setViewKey(key); setTree(null); setTreeError(null); setTreeLoading(true);
    setModalTab('tree'); setEvents([]); setEventsError(null);
    try {
      setTree(await fetchSessionTree(key));
    } catch (e: unknown) {
      setTreeError(e instanceof Error ? e.message : String(e));
    } finally {
      setTreeLoading(false);
    }
  }

  async function loadEvents(key: string) {
    setEventsLoading(true); setEventsError(null);
    try {
      setEvents(await fetchWorkspaceEvents(key));
    } catch (e: unknown) {
      setEventsError(e instanceof Error ? e.message : String(e));
    } finally {
      setEventsLoading(false);
    }
  }

  function switchModalTab(next: 'tree' | 'workspace') {
    if (modalTab === next || !viewKey) return;
    setModalTab(next);
    if (next === 'workspace' && events.length === 0 && !eventsError) {
      loadEvents(viewKey);
    }
  }

  // Client-side filters
  const displayed = sessions.filter(s => {
    if (filterUser && !(s.userId ?? '').toLowerCase().includes(filterUser.toLowerCase())) return false;
    if (filterAgent && !s.agentId.toLowerCase().includes(filterAgent.toLowerCase())) return false;
    return true;
  });

  const uniqueUsers = [...new Set(sessions.map(s => s.userId).filter(Boolean))];

  return (
    <>
      <AdminPageLayout>
      <div style={S.page}>
        {/* Toolbar */}
        <div style={S.toolbar}>
          <h2 style={S.title}>All Sessions</h2>

          <input
            style={S.input}
            placeholder="Filter by user ID…"
            value={filterUser}
            onChange={e => setFilterUser(e.target.value)}
            list="user-suggestions"
          />
          <datalist id="user-suggestions">
            {uniqueUsers.map(u => <option key={u!} value={u!} />)}
          </datalist>

          <input
            style={S.input}
            placeholder="Filter by agent ID…"
            value={filterAgent}
            onChange={e => setFilterAgent(e.target.value)}
          />

          <button style={S.refreshBtn} onClick={load} disabled={loading}>
            {loading ? '…' : '↺ Refresh'}
          </button>

          <span style={{ fontSize: '0.88rem', color: '#64748b', marginLeft: 'auto' }}>
            {displayed.length} / {sessions.length} session{sessions.length !== 1 ? 's' : ''}
          </span>
        </div>

        {error && <p style={{ color: '#dc2626', fontSize: '0.92rem', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '12px 16px', marginBottom: 18 }}>{error}</p>}

        <table style={S.table}>
          <thead>
            <tr>
              <th style={S.th}>Session Key</th>
              <th style={S.th}>User</th>
              <th style={S.th}>Agent</th>
              <th style={S.th}>Kind</th>
              <th style={S.th}>Last Active</th>
              <th style={S.th}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {displayed.map(s => (
              <tr key={s.sessionKey}>
                <td style={{ ...S.td, ...S.mono }} title={s.sessionKey}>
                  {s.sessionKey.slice(0, 36)}{s.sessionKey.length > 36 ? '…' : ''}
                </td>
                <td style={{ ...S.td, ...S.mono }}>{s.userId ?? '—'}</td>
                <td style={{ ...S.td, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.86rem', color: '#4f46e5', fontWeight: 500 }}>{s.agentId}</td>
                <td style={S.td}><span style={{ ...S.kindBadgeBase, background: s.kind === 'MAIN' ? '#dbeafe' : '#f1f5f9', color: s.kind === 'MAIN' ? '#1d4ed8' : '#64748b' }}>{s.kind}</span></td>
                <td style={S.td}>{timeAgo(s.lastActivityMs)}</td>
                <td style={S.td}>
                  <button style={S.viewBtn} onClick={() => openTree(s.sessionKey)}>Tree</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {!loading && displayed.length === 0 && (
          <p style={{ color: '#94a3b8', fontSize: '0.92rem', marginTop: 20 }}>No sessions found.</p>
        )}
      </div>

      {/* Session detail modal — read-only fan-out + workspace evolution */}
      {viewKey && (
        <div style={S.modal} onClick={() => setViewKey(null)}>
          <div style={S.modalBox} onClick={e => e.stopPropagation()}>
            <div style={S.modalHeader}>
              <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.86rem', color: '#475569' }}>
                {viewKey.slice(0, 60)}{viewKey.length > 60 ? '…' : ''}
              </span>
              <button style={S.closeBtn} onClick={() => setViewKey(null)}>✕</button>
            </div>
            <div style={{ display: 'flex', gap: 6, padding: '0 1.5rem', background: '#ffffff', borderBottom: '1px solid #e5e7eb' }}>
              <button
                onClick={() => switchModalTab('tree')}
                style={{
                  background: 'transparent', border: 'none', padding: '12px 18px',
                  cursor: 'pointer', fontSize: '0.9rem',
                  color: modalTab === 'tree' ? '#4338ca' : '#64748b',
                  borderBottom: modalTab === 'tree' ? '2px solid #4f46e5' : '2px solid transparent',
                  fontWeight: modalTab === 'tree' ? 600 : 500,
                }}
              >
                Sub-agent tree
              </button>
              <button
                onClick={() => switchModalTab('workspace')}
                style={{
                  background: 'transparent', border: 'none', padding: '12px 18px',
                  cursor: 'pointer', fontSize: '0.9rem',
                  color: modalTab === 'workspace' ? '#4338ca' : '#64748b',
                  borderBottom: modalTab === 'workspace' ? '2px solid #4f46e5' : '2px solid transparent',
                  fontWeight: modalTab === 'workspace' ? 600 : 500,
                }}
              >
                Workspace evolution
              </button>
            </div>
            <div style={S.modalBody}>
              {modalTab === 'tree' && (
                <>
                  {treeLoading && <p style={{ color: '#64748b' }}>Loading…</p>}
                  {treeError && <p style={{ color: '#dc2626', fontSize: '0.9rem' }}>{treeError}</p>}
                  {tree && (
                    <>
                      <div style={S.detailGrid}>
                        <span style={S.detailKey}>agent</span><span style={S.detailVal}>{tree.session.agentId}</span>
                        <span style={S.detailKey}>user</span><span style={S.detailVal}>{tree.session.userId ?? '—'}</span>
                        <span style={S.detailKey}>kind</span><span style={S.detailVal}>{tree.session.kind}</span>
                        <span style={S.detailKey}>created</span><span style={S.detailVal}>{new Date(tree.session.createdAtMs).toLocaleString()}</span>
                        <span style={S.detailKey}>idle</span><span style={S.detailVal}>{Math.floor(tree.session.idleMs / 1000)}s</span>
                        <span style={S.detailKey}>file</span><span style={S.detailVal}>{tree.session.sessionFilePath ?? '—'}</span>
                      </div>
                      <div style={{ fontSize: '0.82rem', color: '#64748b', marginBottom: 10, textTransform: 'uppercase' as const, letterSpacing: '0.06em', fontWeight: 700 }}>
                        Fan-out
                      </div>
                      <TreeRow node={tree} depth={0} />
                    </>
                  )}
                </>
              )}
              {modalTab === 'workspace' && (
                <>
                  {eventsLoading && <p style={{ color: '#64748b' }}>Loading…</p>}
                  {eventsError && <p style={{ color: '#dc2626', fontSize: '0.9rem' }}>{eventsError}</p>}
                  {!eventsLoading && !eventsError && events.length === 0 && (
                    <p style={{ color: '#64748b', fontSize: '0.92rem' }}>
                      No workspace mutations recorded for this session.
                    </p>
                  )}
                  {!eventsLoading && events.length > 0 && (
                    <table style={{ ...S.table, fontSize: '0.86rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                      <thead>
                        <tr>
                          <th style={S.th}>When</th>
                          <th style={S.th}>Kind</th>
                          <th style={S.th}>Path</th>
                          <th style={S.th}>Tool</th>
                          <th style={S.th}>Size</th>
                        </tr>
                      </thead>
                      <tbody>
                        {events.map((m, i) => (
                          <tr key={`${m.toolCallId ?? i}-${m.path ?? i}-${m.ts}`}>
                            <td style={S.td}>
                              {new Date(m.ts).toLocaleTimeString()}
                              <div style={{ color: '#94a3b8', fontSize: '0.76rem' }}>{timeAgo(m.ts)}</div>
                            </td>
                            <td style={S.td}><span style={kindBadgeStyle(m.kind)}>{m.kind ?? '?'}</span></td>
                            <td style={{ ...S.td, color: '#6d28d9' }} title={m.path ?? ''}>{m.path ?? '–'}</td>
                            <td style={S.td}>{m.toolName ?? '–'}</td>
                            <td style={S.td}>
                              <span style={{ color: '#64748b' }}>{formatBytes(m.preSize)}</span>
                              <span style={{ color: '#cbd5e1' }}> → </span>
                              <span style={{ color: '#0f172a', fontWeight: 500 }}>{formatBytes(m.postSize)}</span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}
      </AdminPageLayout>
    </>
  );
}
