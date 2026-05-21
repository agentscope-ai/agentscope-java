import React, { useCallback, useEffect, useMemo, useState } from 'react';
import AppShell from '../components/AppShell';
import FileTree from '../components/FileTree';
import FileViewer from '../components/FileViewer';
import { createFile, fetchTree } from '../api/workspace';
import type { TreeNode } from '../api/workspace';

const S: Record<string, React.CSSProperties> = {
  layout: { display: 'flex', height: '100%', minHeight: 0 },
  sidebar: {
    width: 280, flexShrink: 0,
    background: '#0d0f18', borderRight: '1px solid #1a1d2e',
    display: 'flex', flexDirection: 'column', overflow: 'hidden',
  },
  toolbar: {
    display: 'flex', alignItems: 'center', gap: 6,
    padding: '8px 10px', borderBottom: '1px solid #1e2235', flexShrink: 0,
  },
  toolbarBtn: {
    background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad',
    borderRadius: 6, padding: '4px 10px', cursor: 'pointer', fontSize: '0.76rem',
  },
  primaryBtn: {
    background: '#6366f1', color: '#fff', border: 'none', borderRadius: 6,
    padding: '4px 12px', cursor: 'pointer', fontSize: '0.78rem', fontWeight: 600,
  },
  treeScroll: { flex: 1, overflow: 'auto', padding: '6px 4px' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 },
  empty: { padding: '0.75rem 1rem', fontSize: '0.78rem', color: '#374056' },
  intro: {
    padding: '8px 10px', fontSize: '0.7rem', color: '#3d4168',
    borderTop: '1px solid #1e2235', lineHeight: 1.5,
  },
};

function findSkillsNode(root: TreeNode): TreeNode | null {
  if (!root.children) return null;
  return root.children.find(c => c.name === 'skills' && c.kind === 'dir') ?? null;
}

export default function SkillsPage() {
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [selected, setSelected] = useState<TreeNode | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setTree(await fetchTree(4));
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const skillsNode = useMemo(() => (tree ? findSkillsNode(tree) : null), [tree]);

  async function newSkill() {
    const name = window.prompt('New skill name (alphanumeric, hyphens, underscores):');
    if (!name) return;
    const safe = name.trim();
    if (!/^[a-zA-Z0-9_\-]+$/.test(safe)) {
      alert('Skill name must match [a-zA-Z0-9_\\-]+');
      return;
    }
    const path = `skills/${safe}.md`;
    const template =
      `---\nname: ${safe}\ndescription: ""\n---\n\n# ${safe}\n\nDescribe what this skill does.\n`;
    try {
      await createFile(path, template);
      await load();
    } catch (e) {
      alert(String(e));
    }
  }

  return (
    <AppShell>
      <div style={S.layout}>
        <div style={S.sidebar}>
          <div style={S.toolbar}>
            <button style={S.toolbarBtn} onClick={load} disabled={loading}>{loading ? '…' : '↺'}</button>
            <button style={S.primaryBtn} onClick={newSkill}>+ New Skill</button>
          </div>

          <div style={S.treeScroll}>
            {err && <div style={{ color: '#f87171', padding: '6px 10px', fontSize: '0.78rem' }}>{err}</div>}
            {skillsNode && skillsNode.children && skillsNode.children.length > 0 ? (
              <FileTree
                root={skillsNode}
                selectedPath={selected?.path ?? null}
                onSelect={setSelected}
                hideRoot
                initialDepth={2}
              />
            ) : (
              !loading && (
                <div style={S.empty}>
                  No skills yet. Click "+ New Skill" to create one.
                </div>
              )
            )}
          </div>

          <div style={S.intro}>
            Skills are Markdown files in your workspace under <code>skills/</code>. Your DataAgent
            loads them automatically on the next chat turn.
          </div>
        </div>

        <div style={S.main}>
          {selected && selected.kind === 'file' ? (
            <FileViewer
              path={selected.path}
              writable
              onDeleted={() => { setSelected(null); load(); }}
            />
          ) : (
            <div
              style={{
                flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#374056', fontSize: '0.85rem', textAlign: 'center', padding: '0 2rem',
                lineHeight: 1.7,
              }}
            >
              {selected
                ? `📁 ${selected.path || '/'} — pick a file to view`
                : 'Pick a skill from the tree, or create a new one.'}
            </div>
          )}
        </div>
      </div>
    </AppShell>
  );
}
