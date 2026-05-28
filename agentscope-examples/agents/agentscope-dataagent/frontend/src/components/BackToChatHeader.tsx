import React from 'react';
import { useNavigate } from 'react-router-dom';

export interface BackToChatHeaderProps {
  title: string;
  subtitle?: string;
}

export default function BackToChatHeader({ title, subtitle }: BackToChatHeaderProps) {
  const navigate = useNavigate();
  return (
    <div style={S.root}>
      <button
        onClick={() => navigate('/chat')}
        style={S.backBtn}
        onMouseEnter={e => { e.currentTarget.style.background = '#eef2ff'; e.currentTarget.style.color = '#3730a3'; }}
        onMouseLeave={e => { e.currentTarget.style.background = '#ffffff'; e.currentTarget.style.color = '#475569'; }}
        title="Return to chat"
      >
        ← 返回 Chat
      </button>
      <div style={S.titleBlock}>
        <span style={S.title}>{title}</span>
        {subtitle && <span style={S.subtitle}>{subtitle}</span>}
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center', gap: 16,
    padding: '12px 24px', borderBottom: '1px solid #e2e8f0',
    background: '#ffffff', flexShrink: 0,
  },
  backBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 6,
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '6px 12px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.84rem', fontWeight: 500,
    transition: 'background 0.12s ease, color 0.12s ease',
    flexShrink: 0,
  },
  titleBlock: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  title: { fontSize: '1rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  subtitle: {
    fontSize: '0.78rem', color: '#64748b',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
};
