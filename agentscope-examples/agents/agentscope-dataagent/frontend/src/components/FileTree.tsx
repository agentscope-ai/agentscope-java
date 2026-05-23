import React, { useState } from 'react';
import type { TreeNode } from '../api/workspace';

interface FileTreeProps {
  root: TreeNode;
  selectedPath: string | null;
  onSelect: (node: TreeNode) => void;
  /** Hide the root node's own row (only its children are rendered at the top level). */
  hideRoot?: boolean;
  /** Optional path-prefix filter — only nodes whose `path` starts with this string are shown. */
  filterPrefix?: string;
  /** Initial expansion depth (0 = collapsed, N = expanded N levels deep). */
  initialDepth?: number;
}

const S: Record<string, React.CSSProperties> = {
  root: {
    fontSize: '0.82rem',
    color: '#b7c0d6',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', sans-serif",
  },
  row: {
    display: 'flex',
    alignItems: 'center',
    padding: '3px 6px',
    cursor: 'pointer',
    borderRadius: 4,
    userSelect: 'none' as const,
    whiteSpace: 'nowrap' as const,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  caret: {
    width: 12,
    fontSize: '0.6rem',
    color: '#4b5280',
    flexShrink: 0,
    marginRight: 2,
  },
  icon: { width: 16, fontSize: '0.8rem', flexShrink: 0, marginRight: 4, textAlign: 'center' as const },
  name: { overflow: 'hidden', textOverflow: 'ellipsis' },
};

function isVisible(node: TreeNode, prefix?: string): boolean {
  if (!prefix) return true;
  // The root carries an empty `path`; that's our anchor — always traverse into it.
  if (node.path === '') return true;
  // Show nodes that ARE inside the prefix or contain the prefix (so ancestor dirs render).
  return node.path.startsWith(prefix) || prefix.startsWith(node.path + '/');
}

function Node({
  node,
  selectedPath,
  onSelect,
  filterPrefix,
  depth,
  defaultOpen,
}: {
  node: TreeNode;
  selectedPath: string | null;
  onSelect: (node: TreeNode) => void;
  filterPrefix?: string;
  depth: number;
  defaultOpen: boolean;
}) {
  const isDir = node.kind === 'dir';
  const [open, setOpen] = useState(defaultOpen);
  const selected = selectedPath === node.path;

  if (!isVisible(node, filterPrefix)) return null;

  return (
    <div>
      <div
        style={{
          ...S.row,
          paddingLeft: 6 + depth * 12,
          background: selected ? '#1e2235' : 'transparent',
          color: selected ? '#c4caff' : isDir ? '#a5b4fc' : '#b7c0d6',
        }}
        onClick={() => {
          if (isDir) setOpen(o => !o);
          onSelect(node);
        }}
      >
        <span style={S.caret}>{isDir ? (open ? '▾' : '▸') : ''}</span>
        <span style={S.icon}>{isDir ? '📁' : '📄'}</span>
        <span style={S.name}>{node.name || node.path || '/'}</span>
      </div>
      {isDir && open && node.children && (
        <div>
          {node.children.map(child => (
            <Node
              key={child.path}
              node={child}
              selectedPath={selectedPath}
              onSelect={onSelect}
              filterPrefix={filterPrefix}
              depth={depth + 1}
              defaultOpen={depth + 1 < (defaultOpen ? 1 : 0)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export default function FileTree({
  root,
  selectedPath,
  onSelect,
  hideRoot = true,
  filterPrefix,
  initialDepth = 1,
}: FileTreeProps) {
  if (hideRoot) {
    return (
      <div style={S.root}>
        {(root.children || []).map(child => (
          <Node
            key={child.path}
            node={child}
            selectedPath={selectedPath}
            onSelect={onSelect}
            filterPrefix={filterPrefix}
            depth={0}
            defaultOpen={initialDepth > 0}
          />
        ))}
      </div>
    );
  }
  return (
    <div style={S.root}>
      <Node
        node={root}
        selectedPath={selectedPath}
        onSelect={onSelect}
        filterPrefix={filterPrefix}
        depth={0}
        defaultOpen={initialDepth > 0}
      />
    </div>
  );
}
