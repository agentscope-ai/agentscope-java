import React, { useEffect, useState } from 'react';
import { listSessions, SessionView } from '../api/sessions';

interface Props {
  activeKey: string | null;
  onSelect: (key: string) => void;
  onNew: () => void;
}

const s: Record<string, React.CSSProperties> = {
  sidebar: {
    width: 260,
    minWidth: 220,
    background: '#ffffff',
    borderRight: '1px solid #e5e7eb',
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    overflowY: 'auto',
  },
  header: {
    padding: '1.1rem 1.25rem',
    borderBottom: '1px solid #e5e7eb',
    fontWeight: 700,
    fontSize: '0.78rem',
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: '0.08em',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  newBtn: {
    background: '#4f46e5',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '3px 10px',
    cursor: 'pointer',
    fontSize: '0.88rem',
    fontWeight: 600,
    boxShadow: '0 1px 2px rgba(79,70,229,0.25)',
  },
  time: { fontSize: '0.78rem', color: '#94a3b8', display: 'block', marginTop: 3 },
};

function itemStyle(active: boolean): React.CSSProperties {
  return {
    padding: '0.75rem 1.25rem',
    cursor: 'pointer',
    background: active ? '#eef2ff' : 'transparent',
    borderLeft: active ? '3px solid #4f46e5' : '3px solid transparent',
    fontSize: '0.9rem',
    color: active ? '#4338ca' : '#334155',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    fontWeight: active ? 600 : 500,
    transition: 'background 0.12s, color 0.12s',
  };
}

function timeAgo(ms: number): string {
  const secs = Math.floor((Date.now() - ms) / 1000);
  if (secs < 60) return 'just now';
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

export default function SessionSidebar({ activeKey, onSelect, onNew }: Props) {
  const [sessions, setSessions] = useState<SessionView[]>([]);

  useEffect(() => {
    listSessions().then(setSessions).catch(() => {});
    const id = setInterval(() => listSessions().then(setSessions).catch(() => {}), 15_000);
    return () => clearInterval(id);
  }, [activeKey]);

  return (
    <div style={s.sidebar}>
      <div style={s.header}>
        <span>Sessions</span>
        <button style={s.newBtn} onClick={onNew} title="New conversation">
          +
        </button>
      </div>
      {sessions.length === 0 && (
        <div style={{ padding: '1.25rem', color: '#94a3b8', fontSize: '0.88rem' }}>
          No sessions yet
        </div>
      )}
      {sessions.map(sess => (
        <div
          key={sess.sessionKey}
          style={itemStyle(sess.sessionKey === activeKey)}
          onClick={() => onSelect(sess.sessionKey)}
          title={sess.sessionKey}
        >
          {sess.label ?? sess.sessionId.slice(0, 12) + '…'}
          <span style={s.time}>{timeAgo(sess.lastActivityMs)}</span>
        </div>
      ))}
    </div>
  );
}
