import React, { useState } from 'react';

interface Props {
  toolName: string;
  toolCallId: string;
  result?: string;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#1a1d27',
    border: '1px solid #2d3148',
    borderRadius: 6,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.82rem',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '0.4rem 0.75rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#1e2235',
  },
  icon: { color: '#6366f1', fontWeight: 700, fontSize: '0.75rem' },
  name: { color: '#a5b4fc', fontWeight: 500 },
  id: { color: '#4b5571', marginLeft: 'auto', fontSize: '0.7rem' },
  body: {
    padding: '0.6rem 0.75rem',
    color: '#94a3b8',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 300,
    overflowY: 'auto',
    fontFamily: 'monospace',
    fontSize: '0.78rem',
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
        <div style={{ ...s.body, color: '#4b5571' }}>Running…</div>
      )}
    </div>
  );
}
