import React, { useEffect, useMemo, useState } from 'react';
import { FileNode, tree as fetchTree, createNode, deleteNode, moveNode } from '../api/workspace';

interface Props {
  agentId: string;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  refreshKey?: number;
  onChange?: () => void;
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 264, flexShrink: 0, borderRight: '1px solid #e2e8f0',
    background: '#ffffff', display: 'flex', flexDirection: 'column',
    minHeight: 0,
  },
  header: {
    padding: '14px 14px', borderBottom: '1px solid #f1f5f9',
    display: 'flex', alignItems: 'center', gap: 8,
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em',
  },
  iconBtn: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 7, padding: '5px 10px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 500,
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '8px 6px' },
  row: {
    display: 'flex', alignItems: 'center', gap: 8,
    padding: '7px 10px', cursor: 'pointer', fontSize: '0.9rem',
    color: '#334155', borderRadius: 7, userSelect: 'none',
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
  },
  rowActive: { background: '#eef2ff', color: '#3730a3', fontWeight: 500 },
  rowHover: { background: '#f8fafc' },
  caret: { width: 12, color: '#94a3b8', flexShrink: 0 },
  err: { padding: 14, fontSize: '0.88rem', color: '#dc2626' },
};

interface NodeViewProps {
  node: FileNode;
  depth: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  onContext: (e: React.MouseEvent, node: FileNode) => void;
  expanded: Set<string>;
  toggle: (path: string) => void;
}

function NodeView({ node, depth, selectedPath, onSelect, onContext, expanded, toggle }: NodeViewProps) {
  const [hover, setHover] = useState(false);
  const isDir = node.type === 'dir';
  const isOpen = expanded.has(node.path);
  const active = selectedPath === node.path;
  const handleClick = () => {
    if (isDir) toggle(node.path);
    else onSelect(node.path);
  };
  return (
    <div>
      <div
        style={{
          ...S.row,
          paddingLeft: 8 + depth * 12,
          ...(active ? S.rowActive : hover ? S.rowHover : {}),
        }}
        onClick={handleClick}
        onContextMenu={e => onContext(e, node)}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        title={node.path}
      >
        <span style={S.caret}>{isDir ? (isOpen ? '▾' : '▸') : ''}</span>
        <span>{isDir ? '📁' : '📄'}</span>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{node.name}</span>
      </div>
      {isDir && isOpen && node.children?.map(c => (
        <NodeView
          key={c.path}
          node={c}
          depth={depth + 1}
          selectedPath={selectedPath}
          onSelect={onSelect}
          onContext={onContext}
          expanded={expanded}
          toggle={toggle}
        />
      ))}
    </div>
  );
}

export default function WorkspaceFileTree({ agentId, selectedPath, onSelect, refreshKey, onChange }: Props) {
  const [nodes, setNodes] = useState<FileNode[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());

  async function reload() {
    setErr(null);
    try {
      const list = await fetchTree(agentId, true);
      setNodes(list);
      // expand all top-level dirs by default on first load
      setExpanded(prev => {
        if (prev.size > 0) return prev;
        const next = new Set<string>();
        for (const n of list) if (n.type === 'dir') next.add(n.path);
        return next;
      });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load files');
    }
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, refreshKey]);

  const toggle = (path: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  async function handleNewFile() {
    const name = window.prompt('New file path (relative, e.g. notes.md or subagents/foo.md)');
    if (!name) return;
    try {
      await createNode(agentId, name, 'file');
      await reload();
      onSelect(name);
      onChange?.();
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : 'Failed');
    }
  }
  async function handleNewDir() {
    const name = window.prompt('New folder path (e.g. skills/my-skill)');
    if (!name) return;
    try {
      await createNode(agentId, name, 'dir');
      await reload();
      onChange?.();
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : 'Failed');
    }
  }

  async function handleContext(e: React.MouseEvent, node: FileNode) {
    e.preventDefault();
    const action = window.prompt(`(${node.path})\nType: rename | delete`, 'rename');
    if (!action) return;
    if (action === 'delete') {
      if (!window.confirm(`Delete ${node.path}?`)) return;
      try {
        await deleteNode(agentId, node.path);
        if (selectedPath === node.path) onSelect('');
        await reload();
        onChange?.();
      } catch (err: unknown) {
        alert(err instanceof Error ? err.message : 'Failed');
      }
    } else if (action === 'rename') {
      const to = window.prompt('Rename to (full new path):', node.path);
      if (!to || to === node.path) return;
      try {
        await moveNode(agentId, node.path, to);
        if (selectedPath === node.path) onSelect(to);
        await reload();
        onChange?.();
      } catch (err: unknown) {
        alert(err instanceof Error ? err.message : 'Failed');
      }
    }
  }

  const list = useMemo(() => nodes, [nodes]);

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={{ flex: 1 }}>Files</span>
        <button style={S.iconBtn} onClick={handleNewFile} title="New file">＋ file</button>
        <button style={S.iconBtn} onClick={handleNewDir} title="New folder">＋ dir</button>
      </div>
      <div style={S.scroll}>
        {err && <div style={S.err}>{err}</div>}
        {!err && list.length === 0 && (
          <div style={{ padding: 14, fontSize: '0.88rem', color: '#94a3b8' }}>Empty workspace.</div>
        )}
        {list.map(n => (
          <NodeView
            key={n.path}
            node={n}
            depth={0}
            selectedPath={selectedPath}
            onSelect={onSelect}
            onContext={handleContext}
            expanded={expanded}
            toggle={toggle}
          />
        ))}
      </div>
    </div>
  );
}
