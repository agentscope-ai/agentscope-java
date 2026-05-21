import React, { useState } from 'react';

interface Props {
  toolName: string;
  toolCallId: string;
  result?: string;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#ffffff',
    border: '1px solid #e5e7eb',
    borderRadius: 10,
    margin: '0.75rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
    boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.55rem 0.95rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#f8fafc',
    borderBottom: '1px solid #e5e7eb',
  },
  icon: { color: '#4f46e5', fontWeight: 700, fontSize: '0.82rem' },
  name: { color: '#4338ca', fontWeight: 600 },
  id: { color: '#94a3b8', marginLeft: 'auto', fontSize: '0.78rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
  body: {
    padding: '0.8rem 0.95rem',
    color: '#334155',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 320,
    overflowY: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.86rem',
    background: '#ffffff',
    lineHeight: 1.6,
  },
};

export default function ToolCallBlock({ toolName, toolCallId, result }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <div style={s.wrapper}>
      <div style={s.header} onClick={() => setOpen(o => !o)}>
        <span style={s.icon}>{open ? '▼' : '▶'}</span>
        <span style={s.name}>Tool: {toolName}</span>
        <span style={s.id}>{toolCallId.slice(0, 10)}</span>
      </div>
      {open && result && <div style={s.body}>{result}</div>}
      {open && !result && (
        <div style={{ ...s.body, color: '#94a3b8' }}>Running…</div>
      )}
    </div>
  );
}
