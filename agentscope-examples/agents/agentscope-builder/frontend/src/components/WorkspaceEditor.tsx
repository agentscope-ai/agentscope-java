import React, { useEffect, useState } from 'react';
import { readFile, writeFile } from '../api/workspace';

interface Props {
  agentId: string;
  path: string | null;
  onSaved?: () => void;
}

const S: Record<string, React.CSSProperties> = {
  root: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, background: '#ffffff' },
  bar: {
    height: 48, padding: '0 18px', display: 'flex', alignItems: 'center', gap: 12,
    borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  pathTxt: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#3730a3', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 },
  saveBtn: {
    padding: '8px 18px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: '0.88rem', fontWeight: 600,
    boxShadow: '0 1px 3px rgba(99,102,241,0.3), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  saveBtnDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
  textarea: {
    flex: 1, padding: '20px 24px', boxSizing: 'border-box',
    background: '#fcfcfd', border: 'none', outline: 'none',
    color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize: '0.92rem', lineHeight: 1.6, resize: 'none', tabSize: 2,
  },
  empty: { padding: 60, color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  status: { fontSize: '0.82rem', color: '#94a3b8' },
  err: { color: '#dc2626' },
  ok: { color: '#059669' },
};

const TEXT_EXT = /\.(md|txt|json|yaml|yml|toml|properties|conf|ini|csv|tsv|xml|html?|css|sql|sh|bash|zsh|java|py|ts|tsx|js|jsx|kt|go|rs|c|cpp|h|hpp)$/i;

export default function WorkspaceEditor({ agentId, path, onSaved }: Props) {
  const [content, setContent] = useState('');
  const [original, setOriginal] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const editable = !!path && (TEXT_EXT.test(path) || path.endsWith('AGENTS.md'));
  const dirty = content !== original;

  useEffect(() => {
    if (!path) {
      setContent(''); setOriginal(''); setErr(null);
      return;
    }
    if (!editable) {
      setContent(''); setOriginal('');
      setErr('Binary or unsupported file type. Upload/download supported via API.');
      return;
    }
    setLoading(true);
    setErr(null);
    readFile(agentId, path)
      .then(text => { setContent(text); setOriginal(text); })
      .catch(e => setErr(e instanceof Error ? e.message : 'Failed to read'))
      .finally(() => setLoading(false));
  }, [agentId, path, editable]);

  async function handleSave() {
    if (!path || !dirty || !editable) return;
    setSaving(true); setErr(null);
    try {
      await writeFile(agentId, path, content);
      setOriginal(content);
      setSavedAt(Date.now());
      onSaved?.();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if ((e.metaKey || e.ctrlKey) && e.key === 's') {
      e.preventDefault();
      handleSave();
    }
  }

  if (!path) {
    return <div style={S.root}><div style={S.empty}>Select a file from the tree to edit.</div></div>;
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <span style={S.pathTxt}>{path}</span>
        {dirty && <span style={S.status}>● modified</span>}
        {!dirty && savedAt && <span style={{ ...S.status, ...S.ok }}>saved</span>}
        {err && <span style={{ ...S.status, ...S.err }}>{err}</span>}
        <button
          style={{ ...S.saveBtn, ...((!dirty || saving || !editable) ? S.saveBtnDisabled : {}) }}
          onClick={handleSave}
          disabled={!dirty || saving || !editable}
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {loading ? (
        <div style={S.empty}>Loading…</div>
      ) : !editable ? (
        <div style={S.empty}>{err ?? 'Cannot edit this file in the browser.'}</div>
      ) : (
        <textarea
          style={S.textarea}
          value={content}
          onChange={e => setContent(e.target.value)}
          onKeyDown={handleKeyDown}
          spellCheck={false}
          autoFocus
        />
      )}
    </div>
  );
}
