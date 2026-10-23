import React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';

const CONFIG_BUTTONS: { key: string; label: string; icon: string }[] = [
  { key: 'skills',    label: 'Skills',    icon: '🛠' },
  { key: 'subagents', label: 'Subagents', icon: '🧩' },
  { key: 'channels',  label: 'Channels',  icon: '📡' },
  { key: 'tools',     label: 'Tools',     icon: '🧰' },
  { key: 'settings',  label: 'Settings',  icon: '⚙' },
];

export interface ChatHeaderProps {
  agent: AgentDefinition | null;
}

export default function ChatHeader({ agent }: ChatHeaderProps) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionKey = searchParams.get('session');

  const canEdit = agent?.tierForCurrentUser === 'EDIT';
  const name = agent?.name ?? 'data-agent';
  const emoji = agent?.identityEmoji ?? '📊';

  return (
    <div style={S.root}>
      <div style={S.left}>
        <span style={S.emoji}>{emoji}</span>
        <div style={S.identity}>
          <span style={S.name}>{name}</span>
          {agent?.description && <span style={S.desc}>{agent.description}</span>}
        </div>
        {sessionKey && (
          <span style={S.sessionTag} title={sessionKey}>
            session: {sessionKey.slice(0, 12)}{sessionKey.length > 12 ? '…' : ''}
          </span>
        )}
      </div>

      <div style={S.right}>
        {canEdit && CONFIG_BUTTONS.map(b => (
          <button
            key={b.key}
            onClick={() => navigate(`/configure/${b.key}`)}
            style={S.btn}
            onMouseEnter={e => { e.currentTarget.style.background = '#eef2ff'; e.currentTarget.style.color = '#3730a3'; }}
            onMouseLeave={e => { e.currentTarget.style.background = '#ffffff'; e.currentTarget.style.color = '#475569'; }}
            title={`Configure ${b.label.toLowerCase()}`}
          >
            <span>{b.icon}</span> {b.label}
          </button>
        ))}
        <button
          onClick={() => navigate('/workspace')}
          style={S.btn}
          onMouseEnter={e => { e.currentTarget.style.background = '#eef2ff'; e.currentTarget.style.color = '#3730a3'; }}
          onMouseLeave={e => { e.currentTarget.style.background = '#ffffff'; e.currentTarget.style.color = '#475569'; }}
          title="Browse workspace files"
        >
          <span>📁</span> Workspace
        </button>
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center', gap: 16,
    padding: '14px 28px', borderBottom: '1px solid #e2e8f0',
    background: '#ffffff', flexShrink: 0,
  },
  left: { display: 'flex', alignItems: 'center', gap: 12, minWidth: 0, flex: 1 },
  emoji: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 36, height: 36, borderRadius: 10,
    background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
    border: '1px solid #c7d2fe',
    fontSize: '1.2rem', flexShrink: 0,
  },
  identity: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  name: { fontSize: '1rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  desc: {
    fontSize: '0.78rem', color: '#64748b',
    maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  sessionTag: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.74rem',
    background: '#f1f5f9', color: '#475569', padding: '3px 8px', borderRadius: 6,
    marginLeft: 8, flexShrink: 0,
  },
  right: { display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 },
  btn: {
    display: 'inline-flex', alignItems: 'center', gap: 6,
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '7px 12px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.84rem', fontWeight: 500,
    transition: 'background 0.12s ease, color 0.12s ease',
  },
};
