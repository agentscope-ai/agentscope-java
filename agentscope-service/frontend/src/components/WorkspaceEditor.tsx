import React, { useEffect, useState } from 'react';
import { readFile } from '../api/workspace';

interface Props {
  agentId: string;
  path: string | null;
}

const S: Record<string, React.CSSProperties> = {
  root: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, background: '#ffffff' },
  bar: {
    height: 48, padding: '0 18px', display: 'flex', alignItems: 'center', gap: 12,
    borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  pathTxt: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#3730a3', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 },
  readonlyBadge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#64748b', border: '1px solid #e2e8f0',
    letterSpacing: '0.02em',
  },
  textarea: {
    flex: 1, padding: '20px 24px', boxSizing: 'border-box',
    background: '#fcfcfd', border: 'none', outline: 'none',
    color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize: '0.92rem', lineHeight: 1.6, resize: 'none', tabSize: 2,
  },
  empty: { padding: 60, color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  status: { fontSize: '0.82rem', color: '#94a3b8' },
  err: { color: '#dc2626' },
};

const TEXT_EXT = /\.(md|txt|json|jsonl|ndjson|log|yaml|yml|toml|properties|conf|ini|csv|tsv|xml|html?|css|sql|sh|bash|zsh|java|py|ts|tsx|js|jsx|kt|go|rs|c|cpp|h|hpp)$/i;

export default function WorkspaceEditor({ agentId, path }: Props) {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const viewable = !!path && (TEXT_EXT.test(path) || path.endsWith('AGENTS.md'));

  useEffect(() => {
    if (!path) {
      setContent(''); setErr(null);
      return;
    }
    if (!viewable) {
      setContent('');
      setErr('Binary or unsupported file type. Download via API.');
      return;
    }
    setLoading(true);
    setErr(null);
    readFile(agentId, path)
      .then(text => setContent(text))
      .catch(e => setErr(e instanceof Error ? e.message : 'Failed to read'))
      .finally(() => setLoading(false));
  }, [agentId, path, viewable]);

  if (!path) {
    return <div style={S.root}><div style={S.empty}>Select a file from the tree to view.</div></div>;
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <span style={S.pathTxt}>{path}</span>
        <span style={S.readonlyBadge}>read-only</span>
        {err && <span style={{ ...S.status, ...S.err }}>{err}</span>}
      </div>
      {loading ? (
        <div style={S.empty}>Loading…</div>
      ) : !viewable ? (
        <div style={S.empty}>{err ?? 'Cannot view this file in the browser.'}</div>
      ) : (
        <textarea
          style={S.textarea}
          value={content}
          readOnly
          spellCheck={false}
        />
      )}
    </div>
  );
}
