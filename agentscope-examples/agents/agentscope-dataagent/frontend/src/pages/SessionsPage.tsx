import React, { useEffect, useState } from 'react';
import AppShell from '../components/AppShell';
import {
  listSessions,
  sessionTurns,
  resetSession,
  listWorkspaceEvents,
  TurnEntry,
  SessionView,
  MutationEntry,
} from '../api/sessions';

const s: Record<string, React.CSSProperties> = {
  content: { padding: '2rem 1.5rem', maxWidth: 900, margin: '0 auto' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' },
  th: {
    textAlign: 'left',
    padding: '0.5rem 0.75rem',
    background: '#13151f',
    color: '#7c8bad',
    borderBottom: '1px solid #1e2235',
    fontWeight: 500,
  },
  td: {
    padding: '0.55rem 0.75rem',
    borderBottom: '1px solid #1e2235',
    color: '#94a3b8',
    verticalAlign: 'top',
  },
  viewBtn: {
    background: '#1e2235',
    border: 'none',
    color: '#a5b4fc',
    borderRadius: 4,
    padding: '2px 10px',
    cursor: 'pointer',
    fontSize: '0.8rem',
    marginRight: 6,
  },
  resetBtn: {
    background: 'transparent',
    border: '1px solid #5b2030',
    color: '#fca5a5',
    borderRadius: 4,
    padding: '2px 9px',
    cursor: 'pointer',
    fontSize: '0.78rem',
  },
  banner: {
    background: '#0d1f14', border: '1px solid #166534', color: '#34d399',
    fontSize: '0.8rem', padding: '6px 10px', borderRadius: 6, marginBottom: 10,
  },
  modal: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.7)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 100,
    padding: '1rem',
  },
  modalBox: {
    background: '#1a1d27',
    border: '1px solid #2d3148',
    borderRadius: 10,
    width: '80vw',
    maxHeight: '85vh',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  },
  modalHeader: {
    padding: '0.75rem 1rem',
    background: '#13151f',
    borderBottom: '1px solid #1e2235',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    fontSize: '0.85rem',
    color: '#7c8bad',
  },
  closeBtn: {
    background: 'transparent',
    border: 'none',
    color: '#7c8bad',
    cursor: 'pointer',
    fontSize: '1.1rem',
  },
  modalBody: {
    flex: 1,
    overflowY: 'auto',
    padding: '1rem',
  },
  tabs: {
    display: 'flex',
    gap: 4,
    padding: '0 1rem',
    background: '#13151f',
    borderBottom: '1px solid #1e2235',
  },
  tab: {
    background: 'transparent',
    border: 'none',
    color: '#7c8bad',
    padding: '8px 14px',
    cursor: 'pointer',
    fontSize: '0.8rem',
    borderBottom: '2px solid transparent',
  },
  tabActive: {
    color: '#a5b4fc',
    borderBottom: '2px solid #6366f1',
  },
  wsTable: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '0.78rem',
    fontFamily: 'monospace',
  },
  wsTh: {
    textAlign: 'left',
    padding: '0.4rem 0.6rem',
    background: '#13151f',
    color: '#7c8bad',
    borderBottom: '1px solid #1e2235',
    fontWeight: 500,
    position: 'sticky',
    top: 0,
  },
  wsTd: {
    padding: '0.4rem 0.6rem',
    borderBottom: '1px solid #1e2235',
    color: '#94a3b8',
    verticalAlign: 'top',
  },
};

function kindBadge(kind: string | null): React.CSSProperties {
  const base: React.CSSProperties = {
    display: 'inline-block',
    padding: '1px 6px',
    borderRadius: 3,
    fontSize: '0.7rem',
    fontWeight: 600,
  };
  if (kind === 'CREATE') return { ...base, background: '#0d1f14', color: '#34d399', border: '1px solid #166534' };
  if (kind === 'EDIT')   return { ...base, background: '#1a1830', color: '#a5b4fc', border: '1px solid #312e81' };
  if (kind === 'DELETE') return { ...base, background: '#1f0d10', color: '#fca5a5', border: '1px solid #5b2030' };
  return { ...base, background: '#1e2235', color: '#7c8bad', border: '1px solid #2d3148' };
}

function formatBytes(n: number): string {
  if (n <= 0) return '–';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function timeAgo(ms: number): string {
  const secs = Math.floor((Date.now() - ms) / 1000);
  if (secs < 60) return 'just now';
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return new Date(ms).toLocaleDateString();
}

function TurnBubble({ turn }: { turn: TurnEntry }) {
  const [expanded, setExpanded] = useState(false);
  const isUser = turn.role === 'USER';
  const isTool = turn.role === 'TOOL' || turn.toolName != null;

  if (isTool && turn.toolName) {
    let inputStr = '';
    try {
      const parsed = JSON.parse(turn.toolInput ?? '{}');
      inputStr = JSON.stringify(parsed, null, 2);
    } catch {
      inputStr = turn.toolInput ?? '';
    }
    let resultStr = '';
    try {
      const parsed = JSON.parse(turn.toolResult ?? 'null');
      resultStr = typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
    } catch {
      resultStr = turn.toolResult ?? '';
    }
    return (
      <div style={{ margin: '6px 0' }}>
        <button
          onClick={() => setExpanded(!expanded)}
          style={{
            background: '#1e2235',
            border: '1px solid #2d3148',
            borderRadius: 6,
            padding: '4px 10px',
            color: '#a5b4fc',
            cursor: 'pointer',
            fontSize: '0.78rem',
            fontFamily: 'monospace',
          }}
        >
          {expanded ? '▼' : '▶'} 🔧 {turn.toolName}
        </button>
        {expanded && (
          <div
            style={{
              background: '#13151f',
              border: '1px solid #2d3148',
              borderRadius: '0 6px 6px 6px',
              padding: '8px 12px',
              marginTop: 2,
              fontSize: '0.75rem',
              fontFamily: 'monospace',
              color: '#94a3b8',
              whiteSpace: 'pre-wrap',
            }}
          >
            {inputStr && (
              <div style={{ marginBottom: 6 }}>
                <span style={{ color: '#7c8bad', fontSize: '0.7rem' }}>INPUT</span>
                <div style={{ color: '#c4b5fd' }}>{inputStr}</div>
              </div>
            )}
            {resultStr && (
              <div>
                <span style={{ color: '#7c8bad', fontSize: '0.7rem' }}>RESULT</span>
                <div style={{ color: '#86efac', maxHeight: 200, overflowY: 'auto' }}>{resultStr}</div>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }

  const bg = isUser ? '#1e2235' : '#13151f';
  const color = isUser ? '#e2e8f0' : '#c4b5fd';
  const align = isUser ? 'flex-end' : 'flex-start';
  const label = isUser ? 'You' : 'Agent';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: align, margin: '8px 0' }}>
      <div style={{ fontSize: '0.7rem', color: '#7c8bad', marginBottom: 2 }}>
        {label} · {new Date(turn.timestampMs).toLocaleTimeString()}
      </div>
      <div
        style={{
          background: bg,
          borderRadius: 8,
          padding: '8px 12px',
          maxWidth: '75%',
          fontSize: '0.83rem',
          color,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          lineHeight: 1.6,
        }}
      >
        {turn.content ?? ''}
      </div>
    </div>
  );
}

export default function SessionsPage() {
  const [sessions, setSessions] = useState<SessionView[]>([]);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [tab, setTab] = useState<'turns' | 'workspace'>('turns');
  const [turns, setTurns] = useState<TurnEntry[]>([]);
  const [events, setEvents] = useState<MutationEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);

  async function reload() {
    try { setSessions(await listSessions(200)); } catch { /* ignore */ }
  }

  useEffect(() => { reload(); }, []);

  async function viewTurns(key: string) {
    setOpenKey(key);
    setTab('turns');
    setTurns([]);
    setEvents([]);
    setLoading(true);
    try {
      const t = await sessionTurns(key);
      setTurns(t);
    } catch {
      setTurns([]);
    } finally {
      setLoading(false);
    }
  }

  async function loadWorkspaceEvents(key: string) {
    setLoading(true);
    try {
      const e = await listWorkspaceEvents(key, undefined, 500);
      setEvents(e);
    } catch {
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }

  function switchTab(next: 'turns' | 'workspace') {
    if (tab === next || !openKey) return;
    setTab(next);
    if (next === 'workspace' && events.length === 0) {
      loadWorkspaceEvents(openKey);
    }
  }

  async function onReset(sess: SessionView) {
    if (!confirm(`Reset session "${sess.sessionKey.slice(0, 32)}…"?\n\nThe key and label are preserved, but the conversation transcript starts fresh.`)) {
      return;
    }
    try {
      await resetSession(sess.sessionKey);
      setBanner(`✓ Session reset (${sess.agentId}).`);
      await reload();
      setTimeout(() => setBanner(null), 4000);
    } catch (e: unknown) {
      setBanner(`✗ Reset failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  return (
    <AppShell>
      <div style={s.content}>
        <h2 style={{ marginBottom: '1.25rem', fontSize: '1.1rem', color: '#e2e8f0' }}>
          Sessions ({sessions.length})
        </h2>

        {banner && <div style={s.banner}>{banner}</div>}

        <table style={s.table}>
          <thead>
            <tr>
              <th style={s.th}>Session Key</th>
              <th style={s.th}>Agent</th>
              <th style={s.th}>Kind</th>
              <th style={s.th}>Last Activity</th>
              <th style={s.th}></th>
            </tr>
          </thead>
          <tbody>
            {sessions.map(sess => (
              <tr key={sess.sessionKey}>
                <td style={s.td} title={sess.sessionKey}>
                  <span style={{ fontFamily: 'monospace', fontSize: '0.75rem' }}>
                    {sess.sessionKey.slice(0, 40)}
                    {sess.sessionKey.length > 40 ? '…' : ''}
                  </span>
                </td>
                <td style={s.td}>{sess.agentId}</td>
                <td style={s.td}>{sess.kind}</td>
                <td style={s.td}>{timeAgo(sess.lastActivityMs)}</td>
                <td style={s.td}>
                  <button style={s.viewBtn} onClick={() => viewTurns(sess.sessionKey)}>
                    View
                  </button>
                  <button style={s.resetBtn} onClick={() => onReset(sess)} title="Clear conversation history; keep label & key.">
                    Reset
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {openKey && (
        <div style={s.modal} onClick={() => setOpenKey(null)}>
          <div style={s.modalBox} onClick={e => e.stopPropagation()}>
            <div style={s.modalHeader}>
              <span style={{ fontFamily: 'monospace', fontSize: '0.75rem' }}>
                {openKey.slice(0, 60)}{openKey.length > 60 ? '…' : ''}
              </span>
              <button style={s.closeBtn} onClick={() => setOpenKey(null)}>✕</button>
            </div>
            <div style={s.tabs}>
              <button
                style={{ ...s.tab, ...(tab === 'turns' ? s.tabActive : {}) }}
                onClick={() => switchTab('turns')}
              >
                Turns
              </button>
              <button
                style={{ ...s.tab, ...(tab === 'workspace' ? s.tabActive : {}) }}
                onClick={() => switchTab('workspace')}
              >
                Workspace
              </button>
            </div>
            <div style={s.modalBody}>
              {tab === 'turns' && (
                <>
                  {loading && (
                    <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>Loading…</p>
                  )}
                  {!loading && turns.length === 0 && (
                    <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>
                      No conversation turns found.
                    </p>
                  )}
                  {!loading && turns.map(t => <TurnBubble key={t.id} turn={t} />)}
                </>
              )}
              {tab === 'workspace' && (
                <>
                  {loading && (
                    <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>Loading…</p>
                  )}
                  {!loading && events.length === 0 && (
                    <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>
                      No workspace mutations recorded for this session.
                    </p>
                  )}
                  {!loading && events.length > 0 && (
                    <table style={s.wsTable}>
                      <thead>
                        <tr>
                          <th style={s.wsTh}>When</th>
                          <th style={s.wsTh}>Kind</th>
                          <th style={s.wsTh}>Path</th>
                          <th style={s.wsTh}>Tool</th>
                          <th style={s.wsTh}>Size</th>
                        </tr>
                      </thead>
                      <tbody>
                        {events.map((m, i) => (
                          <tr key={`${m.toolCallId ?? i}-${m.path ?? i}`}>
                            <td style={s.wsTd}>
                              {new Date(m.ts).toLocaleTimeString()}
                              <div style={{ color: '#5a6478', fontSize: '0.68rem' }}>
                                {timeAgo(m.ts)}
                              </div>
                            </td>
                            <td style={s.wsTd}>
                              <span style={kindBadge(m.kind)}>{m.kind ?? '?'}</span>
                            </td>
                            <td style={s.wsTd} title={m.path ?? ''}>
                              <span style={{ color: '#c4b5fd' }}>{m.path ?? '–'}</span>
                            </td>
                            <td style={s.wsTd}>{m.toolName ?? '–'}</td>
                            <td style={s.wsTd}>
                              <span style={{ color: '#7c8bad' }}>{formatBytes(m.preSize)}</span>
                              <span style={{ color: '#5a6478' }}> → </span>
                              <span>{formatBytes(m.postSize)}</span>
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
    </AppShell>
  );
}
