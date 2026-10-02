import React, { useEffect, useState } from 'react';
import { readFile } from '../api/workspace';

interface Props {
  agentId: string;
  path: string | null;
  refreshKey?: number;
}

const S: Record<string, React.CSSProperties> = {
  root: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, background: '#ffffff' },
  bar: {
    height: 48, padding: '0 18px', display: 'flex', alignItems: 'center', gap: 12,
    borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  pathTxt: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#3730a3', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 },
  readonlyBadge: {
    background: '#f1f5f9', color: '#64748b',
    border: '1px solid #e2e8f0', borderRadius: 6,
    padding: '3px 9px', fontSize: '0.72rem', fontWeight: 600,
    textTransform: 'uppercase', letterSpacing: '0.06em',
  },
  textarea: {
    flex: 1, padding: '20px 24px', boxSizing: 'border-box',
    background: '#fcfcfd', border: 'none', outline: 'none',
    color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize: '0.92rem', lineHeight: 1.6, resize: 'none', tabSize: 2,
    overflow: 'auto',
  },
  wrapToggle: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 6, padding: '3px 9px', cursor: 'pointer',
    fontSize: '0.75rem', fontWeight: 500,
  },
  wrapToggleActive: {
    background: '#eef2ff', borderColor: '#c7d2fe', color: '#4338ca',
  },
  empty: { padding: 60, color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  status: { fontSize: '0.82rem', color: '#94a3b8' },
  err: { color: '#dc2626' },
};

// Files matching this regex are treated as binary and not rendered in the textarea.
// Everything else (including unknown extensions, jsonl, log, diff, dotfiles, files with no
// extension like LICENSE / Dockerfile / Makefile) is displayed as text.
const BINARY_EXT = /\.(png|jpe?g|gif|bmp|ico|webp|tiff?|heic|avif|pdf|docx?|xlsx?|pptx?|odt|ods|odp|zip|tar|t?gz|tbz2?|bz2|xz|7z|rar|jar|war|ear|class|exe|dll|so|dylib|a|o|bin|dat|sqlite3?|db|mdb|pyc|pyo|wasm|mp3|mp4|m4a|wav|flac|ogg|opus|aac|avi|mov|mkv|webm|wmv|ttf|otf|woff2?|eot)$/i;

export default function WorkspaceEditor({ agentId, path, refreshKey }: Props) {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Display only — never affects the textarea's value. Default off so what the user
  // sees matches what is on disk; soft-wrap is opt-in.
  const [softWrap, setSoftWrap] = useState(false);

  const viewable = !!path && !BINARY_EXT.test(path);

  useEffect(() => {
    if (!path) {
      setContent(''); setErr(null);
      return;
    }
    if (!viewable) {
      setContent('');
      setErr('Binary file — preview not supported. Download via API to inspect.');
      return;
    }
    setLoading(true);
    setErr(null);
    readFile(agentId, path)
      .then(text => setContent(text))
      .catch(e => setErr(e instanceof Error ? e.message : 'Failed to read'))
      .finally(() => setLoading(false));
  }, [agentId, path, viewable, refreshKey]);

  if (!path) {
    return <div style={S.root}><div style={S.empty}>Select a file from the tree to view.</div></div>;
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <span style={S.pathTxt}>{path}</span>
        <span style={S.readonlyBadge}>read-only</span>
        {err && <span style={{ ...S.status, ...S.err }}>{err}</span>}
        <button
          type="button"
          style={{ ...S.wrapToggle, ...(softWrap ? S.wrapToggleActive : {}) }}
          onClick={() => setSoftWrap(w => !w)}
          title={softWrap ? 'Soft-wrap on (display only)' : 'No wrap — long lines scroll horizontally'}
        >
          {softWrap ? 'wrap: on' : 'wrap: off'}
        </button>
      </div>
      {loading ? (
        <div style={S.empty}>Loading…</div>
      ) : !viewable ? (
        <div style={S.empty}>{err ?? 'Cannot view this file in the browser.'}</div>
      ) : (
        <textarea
          wrap={softWrap ? 'soft' : 'off'}
          style={{
            ...S.textarea,
            whiteSpace: softWrap ? 'pre-wrap' : 'pre',
            overflowWrap: softWrap ? 'break-word' : 'normal',
            wordBreak: 'normal',
          }}
          value={content}
          readOnly
          spellCheck={false}
        />
      )}
    </div>
  );
}
