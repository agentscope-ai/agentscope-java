import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { clearToken, getToken, isAdmin } from '../api/auth';
import { InboxEntry, deleteSession, inbox } from '../api/sessions';

interface UtilityItem {
  label: string;
  path: string;
  icon: string;
}

const UTILITY_ITEMS: UtilityItem[] = [
  { label: 'Profile', path: '/profile', icon: '👤' },
  { label: 'Appearance', path: '/appearance', icon: '🎨' },
  { label: 'Contributions', path: '/contributions', icon: '🤝' },
  { label: 'Bindings', path: '/bindings', icon: '🔗' },
  { label: 'Usage', path: '/usage', icon: '📈' },
];

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

function getUsername(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.username as string) || (p.sub as string) || '';
}

function startOfDay(d: Date): number {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x.getTime();
}

type Bucket = 'today' | 'yesterday' | 'earlier';

function bucketOf(ms: number): Bucket {
  const today = startOfDay(new Date());
  const yesterday = today - 86_400_000;
  if (ms >= today) return 'today';
  if (ms >= yesterday) return 'yesterday';
  return 'earlier';
}

const BUCKET_LABEL: Record<Bucket, string> = {
  today: 'Today',
  yesterday: 'Yesterday',
  earlier: 'Earlier',
};

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export interface SessionsSidebarProps {
  refreshKey: number;
}

export default function SessionsSidebar({ refreshKey }: SessionsSidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const activeKey = searchParams.get('session');

  const [entries, setEntries] = useState<InboxEntry[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setErr(null);
    setLoading(true);
    inbox(ACTIVE_AGENT_ID, { limit: 100 })
      .then(list => { if (!cancelled) setEntries(list); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [refreshKey]);

  // When the user just clicked 新建对话 the URL carries the freshly-minted conversationId, but
  // no SessionEntry exists on the server yet (it is created on the first message). Surface a
  // synthetic "draft" row in the sidebar so the new chat is immediately visible and selected.
  const draftEntry = useMemo<InboxEntry | null>(() => {
    if (location.pathname !== '/chat' || !activeKey) return null;
    if (entries.some(e => (e.conversationId ?? e.sessionKey) === activeKey)) return null;
    return {
      sessionKey: activeKey,
      sessionId: activeKey,
      agentId: ACTIVE_AGENT_ID,
      conversationId: activeKey,
      label: '新对话',
      lastActivityMs: Date.now(),
      lastMessage: null,
      unread: false,
    };
  }, [location.pathname, activeKey, entries]);

  const grouped = useMemo(() => {
    const map: Record<Bucket, InboxEntry[]> = { today: [], yesterday: [], earlier: [] };
    if (draftEntry) map.today.push(draftEntry);
    for (const e of entries) map[bucketOf(e.lastActivityMs)].push(e);
    return map;
  }, [entries, draftEntry]);

  function handleNewChat() {
    const fresh = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    try { localStorage.setItem(`claw_chat_session:${ACTIVE_AGENT_ID}`, fresh); } catch { /* ignore */ }
    navigate(`/chat?session=${encodeURIComponent(fresh)}`);
  }

  function entryNavKey(entry: InboxEntry): string {
    // Prefer the conversationId so the URL stays stable across turns; fall back to the storage
    // key for legacy entries that pre-date multi-session routing.
    return entry.conversationId ?? entry.sessionKey;
  }

  function openSession(entry: InboxEntry) {
    navigate(`/chat?session=${encodeURIComponent(entryNavKey(entry))}`);
  }

  async function handleDelete(entry: InboxEntry, ev: React.MouseEvent) {
    ev.stopPropagation();
    // The draft row has no backend session yet — just clear the URL/localStorage and let
    // SessionsSidebar drop it on the next render.
    if (draftEntry && entry.sessionKey === draftEntry.sessionKey) {
      try { localStorage.removeItem(`claw_chat_session:${ACTIVE_AGENT_ID}`); } catch { /* ignore */ }
      navigate('/chat');
      return;
    }
    if (!confirm(`Delete this conversation? "${entry.label ?? entry.sessionId}"`)) return;
    try {
      await deleteSession(ACTIVE_AGENT_ID, entryNavKey(entry));
      setEntries(prev => prev.filter(e => e.sessionKey !== entry.sessionKey));
      if (activeKey === entryNavKey(entry)) navigate('/chat');
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div style={S.root}>
      <div style={S.headerRow}>
        <button onClick={handleNewChat} style={S.newBtn}>
          <span style={{ fontSize: '1rem' }}>＋</span> 新建对话
        </button>
      </div>

      <div style={S.scroll}>
        {loading && <div style={S.muted}>Loading…</div>}
        {err && <div style={S.error}>{err}</div>}
        {!loading && !err && entries.length === 0 && !draftEntry && (
          <div style={S.muted}>暂无会话。发送消息即可开始第一段对话。</div>
        )}

        {(['today', 'yesterday', 'earlier'] as Bucket[]).map(b => {
          const list = grouped[b];
          if (list.length === 0) return null;
          return (
            <div key={b} style={S.group}>
              <div style={S.groupLabel}>{BUCKET_LABEL[b]}</div>
              {list.map(e => {
                const isActive = entryNavKey(e) === activeKey && location.pathname === '/chat';
                return (
                  <SessionRow
                    key={e.sessionKey}
                    entry={e}
                    active={isActive}
                    onOpen={() => openSession(e)}
                    onDelete={ev => handleDelete(e, ev)}
                  />
                );
              })}
            </div>
          );
        })}
      </div>

      <div style={S.footer}>
        <UserMenu username={getUsername()} onLogout={() => { clearToken(); navigate('/login', { replace: true }); }} />
      </div>
    </div>
  );
}

interface RowProps {
  entry: InboxEntry;
  active: boolean;
  onOpen: () => void;
  onDelete: (e: React.MouseEvent) => void;
}

function SessionRow({ entry, active, onOpen, onDelete }: RowProps) {
  const [hover, setHover] = useState(false);
  return (
    <div
      onClick={onOpen}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        ...S.row,
        ...(active ? S.rowActive : hover ? S.rowHover : {}),
        ...(entry.unread && !active ? S.rowUnread : {}),
      }}
      title={entry.lastMessage ?? entry.sessionId}
    >
      <div style={S.rowMain}>
        <div style={S.rowTitle}>{entry.label ?? entry.sessionId}</div>
        {entry.lastMessage && <div style={S.rowSnippet}>{entry.lastMessage}</div>}
      </div>
      <div style={S.rowMeta}>
        <span>{relTime(entry.lastActivityMs)}</span>
        {hover && (
          <button
            onClick={onDelete}
            title="Delete conversation"
            style={S.deleteBtn}
          >×</button>
        )}
      </div>
    </div>
  );
}

function UserMenu({ username, onLogout }: { username: string; onLogout: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  const initial = username.charAt(0).toUpperCase() || '?';

  return (
    <div ref={ref} style={{ position: 'relative' as const }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex', alignItems: 'center', gap: 10, width: '100%',
          background: open ? '#eef2ff' : '#f8fafc',
          border: `1px solid ${open ? '#c7d2fe' : '#e2e8f0'}`,
          borderRadius: 10, padding: '8px 12px',
          cursor: 'pointer', color: '#0f172a',
          fontSize: '0.88rem', fontWeight: 500,
        }}
      >
        <div style={{
          width: 28, height: 28, borderRadius: '50%',
          background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
          color: '#ffffff', display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '0.78rem', fontWeight: 700, userSelect: 'none' as const,
        }}>{initial}</div>
        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'left' }}>
          {username || 'User'}
        </span>
        <span style={{ fontSize: '0.6rem', color: '#94a3b8', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }}>▼</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute' as const, bottom: 'calc(100% + 6px)', left: 0, right: 0,
          background: '#ffffff',
          border: '1px solid #e2e8f0', borderRadius: 10,
          boxShadow: '0 12px 28px rgba(15,23,42,0.12)',
          overflow: 'hidden', zIndex: 100,
        }}>
          <div style={{ padding: '10px 14px 8px', borderBottom: '1px solid #f1f5f9' }}>
            <div style={{ fontSize: '0.85rem', fontWeight: 600, color: '#0f172a' }}>{username || 'User'}</div>
          </div>

          {UTILITY_ITEMS.map(item => {
            const active = location.pathname === item.path;
            return (
              <button
                key={item.path}
                onClick={() => { navigate(item.path); setOpen(false); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  width: '100%', background: active ? '#eef2ff' : 'transparent',
                  border: 'none', padding: '10px 14px', cursor: 'pointer',
                  fontSize: '0.85rem', color: active ? '#3730a3' : '#334155',
                  textAlign: 'left' as const, fontWeight: active ? 600 : 500,
                }}
                onMouseEnter={e => { if (!active) e.currentTarget.style.background = '#f8fafc'; }}
                onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
              >
                <span style={{ fontSize: '0.95rem' }}>{item.icon}</span>
                {item.label}
              </button>
            );
          })}

          {isAdmin() && (
            <>
              <div style={{ height: 1, background: '#f1f5f9' }} />
              <button
                onClick={() => { navigate('/admin/overview'); setOpen(false); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  width: '100%', background: 'transparent', border: 'none',
                  padding: '10px 14px', cursor: 'pointer',
                  fontSize: '0.85rem', color: '#334155',
                  textAlign: 'left' as const, fontWeight: 500,
                }}
                onMouseEnter={e => (e.currentTarget.style.background = '#f8fafc')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                <span style={{ fontSize: '0.95rem' }}>🛡</span>
                Admin console
              </button>
            </>
          )}

          <div style={{ height: 1, background: '#f1f5f9' }} />
          <button
            onClick={() => { onLogout(); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 10,
              width: '100%', background: 'transparent', border: 'none',
              padding: '10px 14px', cursor: 'pointer',
              fontSize: '0.85rem', color: '#dc2626',
              textAlign: 'left' as const, fontWeight: 500,
            }}
            onMouseEnter={e => (e.currentTarget.style.background = '#fef2f2')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
          >
            <span style={{ fontSize: '0.95rem' }}>↩</span>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 280, flexShrink: 0,
    background: '#ffffff', borderRight: '1px solid #e2e8f0',
    display: 'flex', flexDirection: 'column', minHeight: 0,
  },
  headerRow: {
    padding: '14px 14px 10px', borderBottom: '1px solid #f1f5f9', flexShrink: 0,
  },
  newBtn: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, width: '100%',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 14px', fontSize: '0.92rem', fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '10px 8px 16px' },
  muted: { padding: '8px 12px', fontSize: '0.85rem', color: '#94a3b8' },
  error: { padding: '8px 12px', fontSize: '0.85rem', color: '#dc2626' },
  group: { marginBottom: 14 },
  groupLabel: {
    fontSize: '0.7rem', fontWeight: 700, letterSpacing: '0.1em',
    color: '#94a3b8', textTransform: 'uppercase', padding: '6px 10px 4px',
  },
  row: {
    display: 'flex', alignItems: 'flex-start', gap: 6,
    padding: '8px 10px', cursor: 'pointer',
    borderRadius: 8, marginBottom: 2,
    border: '1px solid transparent',
  },
  rowActive: { background: '#eef2ff', borderColor: '#c7d2fe' },
  rowHover: { background: '#f8fafc' },
  rowUnread: { borderLeft: '3px solid #6366f1', paddingLeft: 8 },
  rowMain: { flex: 1, minWidth: 0 },
  rowTitle: {
    fontSize: '0.88rem', fontWeight: 500, color: '#0f172a',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  rowSnippet: {
    fontSize: '0.78rem', color: '#94a3b8', marginTop: 2,
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  rowMeta: {
    display: 'flex', alignItems: 'center', gap: 4,
    fontSize: '0.72rem', color: '#94a3b8', flexShrink: 0,
  },
  deleteBtn: {
    background: 'transparent', border: 'none', cursor: 'pointer',
    color: '#94a3b8', fontSize: '1rem', padding: '0 4px',
    borderRadius: 4, lineHeight: 1,
  },
  footer: {
    padding: '12px 14px', borderTop: '1px solid #f1f5f9', flexShrink: 0,
  },
};
