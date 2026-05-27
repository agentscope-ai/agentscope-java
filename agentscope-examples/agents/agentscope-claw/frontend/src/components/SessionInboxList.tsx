import React, { useEffect, useState } from 'react';
import { InboxEntry, inbox } from '../api/sessions';
import { useNavigate, useParams } from 'react-router-dom';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1000 },
  title: { margin: '0 0 18px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  toolbar: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  toggle: {
    fontSize: '0.88rem', color: '#475569', fontWeight: 500,
    display: 'inline-flex', alignItems: 'center', gap: 8, cursor: 'pointer',
  },
  empty: { padding: '60px 0', color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '18px 20px', marginBottom: 12, cursor: 'pointer',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
    transition: 'border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease',
  },
  cardUnread: { borderLeft: '3px solid #6366f1' },
  cardHeader: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 },
  label: { fontSize: '0.98rem', color: '#0f172a', fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  time: { fontSize: '0.8rem', color: '#94a3b8', flexShrink: 0 },
  msg: { fontSize: '0.88rem', color: '#64748b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  cardFooter: {
    display: 'flex', alignItems: 'center', gap: 10, marginTop: 10,
    fontSize: '0.78rem', color: '#94a3b8',
  },
  transcriptLink: {
    color: '#6366f1', cursor: 'pointer', fontWeight: 500, textDecoration: 'none',
  },
  err: { color: '#dc2626', fontSize: '0.9rem' },
};

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export default function SessionInboxList({ agentId }: { agentId: string }) {
  const [entries, setEntries] = useState<InboxEntry[]>([]);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();

  useEffect(() => {
    let cancelled = false;
    setErr(null);
    inbox(agentId, { limit: 50, unreadOnly })
      .then(list => { if (!cancelled) setEntries(list); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); });
    return () => { cancelled = true; };
  }, [agentId, unreadOnly]);

  return (
    <div style={S.root}>
      <h2 style={S.title}>Sessions</h2>
      <div style={S.toolbar}>
        <label style={S.toggle}>
          <input type="checkbox" checked={unreadOnly} onChange={e => setUnreadOnly(e.target.checked)} />
          Unread only
        </label>
      </div>
      {err && <div style={S.err}>{err}</div>}
      {!err && entries.length === 0 && <div style={S.empty}>No sessions yet — try chatting first.</div>}
      {entries.map(e => {
        const aid = id ?? agentId;
        return (
          <div
            key={e.sessionKey}
            style={{ ...S.card, ...(e.unread ? S.cardUnread : {}) }}
            onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/chat?session=${encodeURIComponent(e.sessionKey)}`)}
            title="Resume this conversation in Chat"
            onMouseEnter={ev => {
              ev.currentTarget.style.borderColor = '#c7d2fe';
              ev.currentTarget.style.boxShadow = '0 4px 12px rgba(15,23,42,0.06)';
            }}
            onMouseLeave={ev => {
              ev.currentTarget.style.borderColor = '#e2e8f0';
              ev.currentTarget.style.boxShadow = '0 1px 3px rgba(15,23,42,0.04)';
            }}
          >
            <div style={S.cardHeader}>
              <span style={S.label}>{e.label ?? e.sessionId}</span>
              <span style={S.time}>{relTime(e.lastActivityMs)}</span>
            </div>
            {e.lastMessage && <div style={S.msg}>{e.lastMessage}</div>}
            <div style={S.cardFooter}>
              <span>Click to resume in Chat</span>
              <span style={{ flex: 1 }} />
              <span
                style={S.transcriptLink}
                onClick={ev => {
                  ev.stopPropagation();
                  navigate(`/agents/${encodeURIComponent(aid)}/sessions/${encodeURIComponent(e.sessionKey)}`);
                }}
              >
                View transcript →
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
