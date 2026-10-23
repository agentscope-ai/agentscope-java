import React, { useState } from 'react';

interface Props {
  toolName: string;
  toolCallId: string;
  result?: string;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.6rem 0.9rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#eef2ff',
    borderBottom: '1px solid #e2e8f0',
  },
  icon: { color: '#6366f1', fontWeight: 700, fontSize: '0.82rem' },
  name: { color: '#3730a3', fontWeight: 600 },
  id: { color: '#94a3b8', marginLeft: 'auto', fontSize: '0.78rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
  body: {
    padding: '0.85rem 1rem',
    color: '#334155',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 320,
    overflowY: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem',
    lineHeight: 1.55,
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
