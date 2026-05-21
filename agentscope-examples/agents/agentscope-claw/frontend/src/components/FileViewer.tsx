import React, { useEffect, useState } from 'react';
import { readFile, writeFile, deletePath } from '../api/workspace';
import type { FileContent } from '../api/workspace';

interface FileViewerProps {
  path: string | null;
  /** When true, the file content can be edited and saved. */
  writable: boolean;
  /** Notify parent so it can refresh the tree (e.g. after delete). */
  onDeleted?: (path: string) => void;
}

const S: Record<string, React.CSSProperties> = {
  wrap: { display: 'flex', flexDirection: 'column', height: '100%' },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 10px',
    borderBottom: '1px solid #1e2235',
    flexShrink: 0,
  },
  path: { fontSize: '0.82rem', color: '#a5b4fc', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  meta: { fontSize: '0.7rem', color: '#4b5280' },
  spacer: { flex: 1 },
  btn: {
    background: '#6366f1', color: '#fff', border: 'none', borderRadius: 6,
    padding: '5px 12px', cursor: 'pointer', fontSize: '0.78rem', fontWeight: 600,
  },
  btnSecondary: {
    background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad',
    borderRadius: 6, padding: '5px 10px', cursor: 'pointer', fontSize: '0.78rem',
  },
  btnDelete: {
    background: 'transparent', border: '1px solid #5b2030', color: '#f87171',
    borderRadius: 6, padding: '5px 10px', cursor: 'pointer', fontSize: '0.78rem',
  },
  textarea: {
    flex: 1, width: '100%', boxSizing: 'border-box' as const,
    background: '#0d0f18', border: 'none', borderTop: '1px solid #1e2235',
    color: '#a5b4fc', fontFamily: 'monospace', fontSize: '0.8rem',
    padding: '0.75rem', resize: 'none' as const, outline: 'none',
  },
  empty: {
    flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
    color: '#374056', fontSize: '0.85rem',
  },
  binary: {
    flex: 1, padding: '1rem', fontSize: '0.82rem', color: '#7c8bad',
    background: '#0d0f18',
  },
  msg: { fontSize: '0.75rem' },
  err: { color: '#f87171', padding: '0.75rem 1rem', background: '#1f1520', fontSize: '0.82rem' },
};

function fmtSize(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

export default function FileViewer({ path, writable, onDeleted }: FileViewerProps) {
  const [content, setContent] = useState<FileContent | null>(null);
  const [draft, setDraft] = useState<string>('');
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (!path) {
      setContent(null);
      setDraft('');
      setDirty(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setErr(null);
    setMsg(null);
    readFile(path)
      .then(c => {
        if (cancelled) return;
        setContent(c);
        setDraft(c.text ?? '');
        setDirty(false);
      })
      .catch(e => {
        if (cancelled) return;
        setContent(null);
        setErr(String(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  async function save() {
    if (!path) return;
    setSaving(true);
    setMsg(null);
    try {
      await writeFile(path, draft);
      setDirty(false);
      setMsg({ ok: true, text: 'Saved' });
    } catch (e) {
      setMsg({ ok: false, text: String(e) });
    } finally {
      setSaving(false);
    }
  }

  async function del() {
    if (!path) return;
    if (!confirm(`Delete "${path}"?`)) return;
    try {
      await deletePath(path);
      if (onDeleted) onDeleted(path);
    } catch (e) {
      setMsg({ ok: false, text: String(e) });
    }
  }

  if (!path) {
    return <div style={S.empty}>Select a file from the tree.</div>;
  }
  if (loading) {
    return <div style={S.empty}>Loading…</div>;
  }
  if (err) {
    return <div style={S.err}>{err}</div>;
  }
  if (!content) return null;

  return (
    <div style={S.wrap}>
      <div style={S.header}>
        <span style={S.path}>{path}</span>
        <span style={S.meta}>{fmtSize(content.sizeBytes)}</span>
        <div style={S.spacer} />
        {writable && (
          <>
            <button
              style={S.btn}
              onClick={save}
              disabled={saving || !dirty || content.binary}
              title={content.binary ? 'Binary files are not editable here' : ''}
            >
              {saving ? 'Saving…' : dirty ? 'Save' : 'Saved'}
            </button>
            <button style={S.btnDelete} onClick={del}>
              Delete
            </button>
          </>
        )}
        {msg && (
          <span style={{ ...S.msg, color: msg.ok ? '#4ade80' : '#f87171' }}>{msg.text}</span>
        )}
      </div>

      {content.binary ? (
        <div style={S.binary}>
          Binary file ({fmtSize(content.sizeBytes)}). Use a download client to retrieve it.
        </div>
      ) : (
        <textarea
          style={S.textarea}
          value={draft}
          onChange={e => {
            setDraft(e.target.value);
            setDirty(true);
            setMsg(null);
          }}
          spellCheck={false}
          readOnly={!writable}
          onKeyDown={e => {
            if (writable && (e.ctrlKey || e.metaKey) && e.key === 's') {
              e.preventDefault();
              save();
            }
          }}
        />
      )}
    </div>
  );
}
