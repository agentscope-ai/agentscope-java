import React, { useEffect, useState } from 'react';
import { listSessions, SessionView } from '../api/sessions';

interface Props {
  activeKey: string | null;
  onSelect: (key: string) => void;
  onNew: () => void;
}

const s: Record<string, React.CSSProperties> = {
  sidebar: {
    width: 240,
    minWidth: 200,
    background: '#13151f',
    borderRight: '1px solid #1e2235',
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    overflowY: 'auto',
  },
  header: {
    padding: '1rem',
    borderBottom: '1px solid #1e2235',
    fontWeight: 600,
    fontSize: '0.85rem',
    color: '#7c8bad',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  newBtn: {
    background: '#6366f1',
    color: '#fff',
    border: 'none',
    borderRadius: 4,
    padding: '2px 8px',
    cursor: 'pointer',
    fontSize: '0.8rem',
  },
  time: { fontSize: '0.7rem', color: '#4b5571', display: 'block', marginTop: 2 },
};

function itemStyle(active: boolean): React.CSSProperties {
  return {
    padding: '0.65rem 1rem',
    cursor: 'pointer',
    background: active ? '#1e2235' : 'transparent',
    borderLeft: active ? '3px solid #6366f1' : '3px solid transparent',
    fontSize: '0.82rem',
    color: active ? '#e2e8f0' : '#94a3b8',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
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
        <div style={{ padding: '1rem', color: '#4b5571', fontSize: '0.8rem' }}>
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
