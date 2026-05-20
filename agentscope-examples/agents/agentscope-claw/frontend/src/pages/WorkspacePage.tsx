import React, { useCallback, useEffect, useRef, useState } from 'react';
import AppShell from '../components/AppShell';
import FileTree from '../components/FileTree';
import FileViewer from '../components/FileViewer';
import {
  createDir,
  createFile,
  fetchTree,
  uploadFile,
} from '../api/workspace';
import type { TreeNode } from '../api/workspace';

const WRITABLE_PREFIXES = ['skills/', 'subagents/', 'knowledge/', 'memory/'];
const WRITABLE_FILES = new Set(['AGENTS.md']);

function isWritable(path: string | null): boolean {
  if (!path) return false;
  if (WRITABLE_FILES.has(path)) return true;
  return WRITABLE_PREFIXES.some(p => path.startsWith(p));
}

const S: Record<string, React.CSSProperties> = {
  layout: { display: 'flex', height: '100%', minHeight: 0 },
  sidebar: {
    width: 300,
    flexShrink: 0,
    background: '#0d0f18',
    borderRight: '1px solid #1a1d2e',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  },
  toolbar: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '8px 10px',
    borderBottom: '1px solid #1e2235',
    flexShrink: 0,
  },
  toolbarBtn: {
    background: 'transparent',
    border: '1px solid #2d3148',
    color: '#7c8bad',
    borderRadius: 6,
    padding: '4px 10px',
    cursor: 'pointer',
    fontSize: '0.76rem',
  },
  treeScroll: { flex: 1, overflow: 'auto', padding: '6px 4px' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 },
  empty: { padding: '0.75rem 1rem', fontSize: '0.78rem', color: '#374056' },
  hint: { padding: '6px 10px', fontSize: '0.7rem', color: '#3d4168', borderTop: '1px solid #1e2235' },
};

export default function WorkspacePage() {
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [selected, setSelected] = useState<TreeNode | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setTree(await fetchTree());
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function newFile() {
    const name = window.prompt(
      'New file path (must start with one of: skills/, subagents/, knowledge/, memory/, or be AGENTS.md):',
    );
    if (!name) return;
    try {
      await createFile(name.trim(), '');
      await load();
    } catch (e) {
      alert(String(e));
    }
  }

  async function newDir() {
    const name = window.prompt(
      'New directory path (must start with one of: skills/, subagents/, knowledge/, memory/):',
    );
    if (!name) return;
    try {
      await createDir(name.trim());
      await load();
    } catch (e) {
      alert(String(e));
    }
  }

  async function onUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadFile(file, 'knowledge');
      await load();
    } catch (err2) {
      alert(String(err2));
    } finally {
      e.target.value = '';
    }
  }

  function onDeleted() {
    setSelected(null);
    load();
  }

  return (
    <AppShell>
      <div style={S.layout}>
        <div style={S.sidebar}>
          <div style={S.toolbar}>
            <button style={S.toolbarBtn} onClick={load} disabled={loading} title="Refresh">
              {loading ? '…' : '↺'}
            </button>
            <button style={S.toolbarBtn} onClick={newFile} title="New file">
              + File
            </button>
            <button style={S.toolbarBtn} onClick={newDir} title="New folder">
              + Folder
            </button>
            <button
              style={S.toolbarBtn}
              onClick={() => fileInput.current?.click()}
              title="Upload to knowledge/"
            >
              ⤴ Upload
            </button>
            <input
              ref={fileInput}
              type="file"
              style={{ display: 'none' }}
              onChange={onUpload}
            />
          </div>

          <div style={S.treeScroll}>
            {err && <div style={{ color: '#f87171', padding: '6px 10px', fontSize: '0.78rem' }}>{err}</div>}
            {tree && tree.children && tree.children.length > 0 ? (
              <FileTree
                root={tree}
                selectedPath={selected?.path ?? null}
                onSelect={setSelected}
              />
            ) : (
              !loading && <div style={S.empty}>Workspace is empty.</div>
            )}
          </div>

          <div style={S.hint}>
            Writable: AGENTS.md, skills/, subagents/, knowledge/, memory/
          </div>
        </div>

        <div style={S.main}>
          {selected && selected.kind === 'file' ? (
            <FileViewer
              path={selected.path}
              writable={isWritable(selected.path)}
              onDeleted={onDeleted}
            />
          ) : (
            <div
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#374056',
                fontSize: '0.85rem',
                lineHeight: 1.7,
                textAlign: 'center',
                padding: '0 2rem',
              }}
            >
              {selected
                ? `📁 ${selected.path || '/'} — pick a file from the tree`
                : 'Browse your workspace files here.\nUse the toolbar to create, upload, or refresh.'}
            </div>
          )}
        </div>
      </div>
    </AppShell>
  );
}
