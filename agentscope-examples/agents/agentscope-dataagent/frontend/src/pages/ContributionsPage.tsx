import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  listMyContributions,
  submitFromWorkspace,
  type Contribution,
  type ContributionTargetType,
} from '../api/contributions';
import { tree as fetchTree, type FileNode } from '../api/workspace';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';

const S: Record<string, React.CSSProperties> = {
  page: {
    padding: '20px 24px',
    display: 'flex',
    flexDirection: 'column',
    gap: 16,
    height: '100%',
    overflow: 'auto',
  },
  header: { display: 'flex', alignItems: 'baseline', gap: 12 },
  h1: { fontSize: '1.05rem', fontWeight: 600, color: '#e2e8f0' },
  sub: { fontSize: '0.78rem', color: '#7c8bad' },
  panel: { background: '#0d0f18', border: '1px solid #1a1d2e', borderRadius: 8, padding: 16 },
  panelTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#c4caff', marginBottom: 10 },
  submitGrid: {
    display: 'grid',
    gridTemplateColumns: 'minmax(280px, 1fr) minmax(320px, 1fr)',
    gap: 16,
  },
  treeBox: {
    background: '#0f1117',
    border: '1px solid #1e2235',
    borderRadius: 6,
    maxHeight: 360,
    overflowY: 'auto',
    padding: '6px 4px',
  },
  row: { display: 'flex', gap: 8, marginBottom: 8 },
  label: { fontSize: '0.72rem', color: '#7c8bad', marginBottom: 4, display: 'block' },
  input: {
    width: '100%',
    background: '#0f1117',
    color: '#e2e8f0',
    border: '1px solid #2d3148',
    borderRadius: 6,
    padding: '6px 8px',
    fontSize: '0.8rem',
  },
  btn: {
    background: '#6366f1',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '6px 14px',
    cursor: 'pointer',
    fontSize: '0.8rem',
    fontWeight: 600,
  },
  btnDisabled: {
    background: '#312e81',
    color: '#94a3b8',
    cursor: 'not-allowed',
  },
  table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.78rem' },
  th: {
    textAlign: 'left' as const,
    padding: '6px 8px',
    borderBottom: '1px solid #1e2235',
    color: '#7c8bad',
    fontWeight: 500,
    fontSize: '0.72rem',
    textTransform: 'uppercase' as const,
  },
  td: {
    padding: '8px',
    borderBottom: '1px solid #131726',
    color: '#cbd5e1',
    verticalAlign: 'top' as const,
  },
  err: { color: '#f87171', fontSize: '0.78rem' },
  ok: { color: '#86efac', fontSize: '0.78rem' },
  pill: {
    display: 'inline-block',
    padding: '1px 6px',
    borderRadius: 4,
    background: '#1e2235',
    color: '#c4caff',
    fontSize: '0.68rem',
    fontWeight: 600,
    marginLeft: 6,
  },
  treeRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '3px 8px',
    fontSize: '0.8rem',
    color: '#cbd5e1',
    cursor: 'pointer',
    userSelect: 'none' as const,
  },
};

const TYPES: ContributionTargetType[] = ['skill', 'subagent', 'memory', 'agents_md', 'knowledge'];

function badgeStyle(status: string): React.CSSProperties {
  return {
    display: 'inline-block',
    padding: '2px 8px',
    borderRadius: 4,
    fontSize: '0.7rem',
    fontWeight: 600,
    background:
      status === 'APPROVED' ? '#1e3a2b' : status === 'REJECTED' ? '#3a1e1e' : '#1e2235',
    color: status === 'APPROVED' ? '#86efac' : status === 'REJECTED' ? '#fca5a5' : '#c4caff',
  };
}

// Hide internal/dotfile entries from the picker — same convention as WorkspaceFileTree.
const INTERNAL_BASENAMES = new Set(['_install.meta.json']);
function isHiddenName(name: string): boolean {
  return name.startsWith('.') || INTERNAL_BASENAMES.has(name);
}
function filterTree(nodes: FileNode[]): FileNode[] {
  const out: FileNode[] = [];
  for (const n of nodes) {
    if (isHiddenName(n.name)) continue;
    if (n.type === 'dir' && n.children) {
      out.push({ ...n, children: filterTree(n.children) });
    } else {
      out.push(n);
    }
  }
  return out;
}

interface Inferred {
  type: ContributionTargetType;
  path: string;
  warning?: string;
}

/**
 * Infer the target type and the canonical {@code targetPath} from the first selected workspace
 * path. Returns null when nothing is selected. The user can override the type via the dropdown
 * and the path via the input box; this just seeds reasonable defaults.
 */
function inferTarget(selected: string[]): Inferred | null {
  if (selected.length === 0) return null;
  const first = selected[0];
  const segs = first.split('/');
  if (first === 'AGENTS.md') {
    if (selected.length > 1) {
      return { type: 'agents_md', path: 'AGENTS.md', warning: 'agents_md takes a single file.' };
    }
    return { type: 'agents_md', path: 'AGENTS.md' };
  }
  if (segs[0] === 'skills' && segs.length >= 2) {
    const bundle = segs[1];
    const mismatch = selected.some(p => {
      const s = p.split('/');
      return s[0] !== 'skills' || s[1] !== bundle;
    });
    return {
      type: 'skill',
      path: bundle,
      warning: mismatch
        ? `All selected files must live under skills/${bundle}/ for one bundle.`
        : undefined,
    };
  }
  if (segs[0] === 'subagents' && segs.length >= 2) {
    return {
      type: 'subagent',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'subagent takes a single file.' : undefined,
    };
  }
  if (segs[0] === 'memory' && segs.length >= 2) {
    return {
      type: 'memory',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'memory takes a single file.' : undefined,
    };
  }
  if (segs[0] === 'knowledge' && segs.length >= 2) {
    return {
      type: 'knowledge',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'knowledge takes a single file.' : undefined,
    };
  }
  return {
    type: 'knowledge',
    path: segs[segs.length - 1],
    warning: `Path "${first}" doesn't match a known root; defaulting to knowledge.`,
  };
}

interface TreeRowProps {
  node: FileNode;
  depth: number;
  expanded: Set<string>;
  selected: Set<string>;
  toggleExpand: (p: string) => void;
  toggleSelect: (p: string) => void;
}

function TreeRow({ node, depth, expanded, selected, toggleExpand, toggleSelect }: TreeRowProps) {
  const isDir = node.type === 'dir';
  const isOpen = expanded.has(node.path);
  return (
    <div>
      <div
        style={{ ...S.treeRow, paddingLeft: 8 + depth * 14 }}
        onClick={() => (isDir ? toggleExpand(node.path) : toggleSelect(node.path))}
        title={node.path}
      >
        <span style={{ width: 10, color: '#7c8bad' }}>{isDir ? (isOpen ? '▾' : '▸') : ''}</span>
        {!isDir && (
          <input
            type="checkbox"
            checked={selected.has(node.path)}
            onChange={() => toggleSelect(node.path)}
            onClick={e => e.stopPropagation()}
          />
        )}
        <span>{isDir ? '📁' : '📄'}</span>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{node.name}</span>
      </div>
      {isDir &&
        isOpen &&
        node.children?.map(c => (
          <TreeRow
            key={c.path}
            node={c}
            depth={depth + 1}
            expanded={expanded}
            selected={selected}
            toggleExpand={toggleExpand}
            toggleSelect={toggleSelect}
          />
        ))}
    </div>
  );
}

export default function ContributionsPage() {
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [sourceAgentId, setSourceAgentId] = useState(ACTIVE_AGENT_ID);
  const [targetAgentId, setTargetAgentId] = useState('');
  const [nodes, setNodes] = useState<FileNode[]>([]);
  const [treeErr, setTreeErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  const [overrideType, setOverrideType] = useState<ContributionTargetType | ''>('');
  const [overridePath, setOverridePath] = useState('');
  const [rationale, setRationale] = useState('');
  const [submitErr, setSubmitErr] = useState<string | null>(null);
  const [submitOk, setSubmitOk] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listMyContributions());
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadTree = useCallback(async (agentId: string) => {
    setTreeErr(null);
    try {
      const list = await fetchTree(agentId, true);
      const visible = filterTree(list);
      setNodes(visible);
      setExpanded(prev => {
        if (prev.size > 0) return prev;
        const next = new Set<string>();
        for (const n of visible) if (n.type === 'dir') next.add(n.path);
        return next;
      });
    } catch (e: unknown) {
      setTreeErr(e instanceof Error ? e.message : 'Failed to load files');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);
  useEffect(() => {
    reloadTree(sourceAgentId);
    setSelected(new Set());
  }, [sourceAgentId, reloadTree]);

  const toggleExpand = (p: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };
  const toggleSelect = (p: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };

  const selectedList = useMemo(() => Array.from(selected).sort(), [selected]);
  const inferred = useMemo(() => inferTarget(selectedList), [selectedList]);

  const effectiveType: ContributionTargetType | '' = overrideType || inferred?.type || '';
  const effectivePath = overridePath || inferred?.path || '';

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitErr(null);
    setSubmitOk(null);
    if (selectedList.length === 0) {
      setSubmitErr('Pick at least one file from the workspace tree.');
      return;
    }
    if (!effectiveType) {
      setSubmitErr('Target type is required.');
      return;
    }
    if (!effectivePath.trim()) {
      setSubmitErr('Target path is required.');
      return;
    }
    setSubmitting(true);
    try {
      const created = await submitFromWorkspace({
        sourceAgentId,
        targetAgentId: targetAgentId.trim() || null,
        targetType: effectiveType,
        targetPath: effectivePath.trim(),
        rationale: rationale.trim() || null,
        sourcePaths: selectedList,
      });
      setSubmitOk(`Submitted as #${created.id} — awaiting admin approval.`);
      setSelected(new Set());
      setOverridePath('');
      setOverrideType('');
      setRationale('');
      load();
    } catch (ex) {
      setSubmitErr(String(ex));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.page}>
      <div style={S.header}>
        <div style={S.h1}>Contributions</div>
        <div style={S.sub}>
          Pick workspace files to nominate for the shared agent layer — admin approval required.
        </div>
      </div>

      <form onSubmit={onSubmit} style={S.panel}>
        <div style={S.panelTitle}>Submit a new contribution</div>
        <div style={S.submitGrid}>
          <div>
            <div style={S.row}>
              <div style={{ flex: 1 }}>
                <span style={S.label}>Source agent id</span>
                <input
                  style={S.input}
                  value={sourceAgentId}
                  onChange={e => setSourceAgentId(e.target.value)}
                  placeholder={ACTIVE_AGENT_ID}
                />
              </div>
              <div style={{ flex: 1 }}>
                <span style={S.label}>Target agent id (optional)</span>
                <input
                  style={S.input}
                  value={targetAgentId}
                  onChange={e => setTargetAgentId(e.target.value)}
                  placeholder={`defaults to ${sourceAgentId || 'source'}`}
                />
              </div>
            </div>
            <span style={S.label}>
              Select files{' '}
              <span style={S.pill}>{selectedList.length} selected</span>
            </span>
            <div style={S.treeBox}>
              {treeErr && <div style={{ ...S.err, padding: 8 }}>{treeErr}</div>}
              {!treeErr && nodes.length === 0 && (
                <div style={{ padding: 8, fontSize: '0.78rem', color: '#7c8bad' }}>
                  Workspace is empty.
                </div>
              )}
              {nodes.map(n => (
                <TreeRow
                  key={n.path}
                  node={n}
                  depth={0}
                  expanded={expanded}
                  selected={selected}
                  toggleExpand={toggleExpand}
                  toggleSelect={toggleSelect}
                />
              ))}
            </div>
          </div>

          <div>
            <div style={S.row}>
              <div style={{ flex: 1 }}>
                <span style={S.label}>Type</span>
                <select
                  style={{ ...S.input, padding: '5px 8px' }}
                  value={overrideType || inferred?.type || 'skill'}
                  onChange={e => setOverrideType(e.target.value as ContributionTargetType)}
                >
                  {TYPES.map(t => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
              <div style={{ flex: 2 }}>
                <span style={S.label}>Target path</span>
                <input
                  style={S.input}
                  value={overridePath || inferred?.path || ''}
                  onChange={e => setOverridePath(e.target.value)}
                  placeholder={
                    (overrideType || inferred?.type) === 'skill'
                      ? 'bundle name, e.g. cohort-builder'
                      : (overrideType || inferred?.type) === 'agents_md'
                      ? 'AGENTS.md'
                      : 'file path under the type directory'
                  }
                />
              </div>
            </div>
            <div style={{ marginBottom: 8 }}>
              <span style={S.label}>Rationale for the reviewing admin (optional)</span>
              <input
                style={S.input}
                value={rationale}
                onChange={e => setRationale(e.target.value)}
              />
            </div>
            <div style={{ marginBottom: 8 }}>
              <span style={S.label}>Selected files</span>
              <div
                style={{
                  ...S.input,
                  minHeight: 60,
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  fontSize: '0.74rem',
                  whiteSpace: 'pre-wrap' as const,
                }}
              >
                {selectedList.length === 0 ? '—' : selectedList.join('\n')}
              </div>
            </div>
            {inferred?.warning && (
              <div style={{ ...S.err, marginBottom: 8 }}>{inferred.warning}</div>
            )}
            <button
              type="submit"
              style={{
                ...S.btn,
                ...(submitting || selectedList.length === 0 ? S.btnDisabled : {}),
              }}
              disabled={submitting || selectedList.length === 0}
            >
              {submitting ? 'Submitting…' : 'Submit'}
            </button>
            {submitErr && <div style={{ ...S.err, marginTop: 8 }}>{submitErr}</div>}
            {submitOk && <div style={{ ...S.ok, marginTop: 8 }}>{submitOk}</div>}
          </div>
        </div>
      </form>

      <div style={S.panel}>
        <div style={S.panelTitle}>My submissions</div>
        {loading && <div style={S.sub}>Loading…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && !err && items.length === 0 && (
          <div style={S.sub}>You haven't submitted any contributions yet.</div>
        )}
        {!loading && items.length > 0 && (
          <table style={S.table}>
            <thead>
              <tr>
                <th style={S.th}>#</th>
                <th style={S.th}>Status</th>
                <th style={S.th}>Type</th>
                <th style={S.th}>Target agent</th>
                <th style={S.th}>Path</th>
                <th style={S.th}>Submitted</th>
                <th style={S.th}>Reviewer note</th>
              </tr>
            </thead>
            <tbody>
              {items.map(c => (
                <tr key={c.id}>
                  <td style={S.td}>{c.id}</td>
                  <td style={S.td}>
                    <span style={badgeStyle(c.status)}>{c.status}</span>
                  </td>
                  <td style={S.td}>{c.targetType}</td>
                  <td style={S.td}>{c.targetAgentId || c.sourceAgentId || '—'}</td>
                  <td style={S.td}>
                    <code>{c.targetPath}</code>
                  </td>
                  <td style={S.td}>{new Date(c.createdAt).toLocaleString()}</td>
                  <td style={S.td}>{c.reviewerNote || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
