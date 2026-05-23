import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';
import SubagentPanel from '../components/SubagentPanel';
import { summary as fetchSummary, WorkspaceSummary } from '../api/workspace';

type Tab = 'files' | 'subagents';

const tabStyle: React.CSSProperties = {
  padding: '8px 16px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  border: 'none', background: 'transparent', color: '#64748b', borderBottom: '2px solid transparent',
};
const tabActive: React.CSSProperties = { color: '#4338ca', borderBottomColor: '#4338ca' };

const pathBar: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '6px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#f8fafc', flexShrink: 0,
  fontSize: '0.78rem', color: '#64748b',
};
const pathLabel: React.CSSProperties = {
  fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.08em',
};
const pathValue: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: '#334155', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  flex: 1, minWidth: 0,
};

export default function AgentWorkspacePage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [tab, setTab] = useState<Tab>('files');
  const [summary, setSummary] = useState<WorkspaceSummary | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchSummary(agentId)
      .then(s => { if (!cancelled) setSummary(s); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [agentId]);

  const isSubagentFile = selected?.startsWith('subagents/') && selected.endsWith('.md');

  async function copyPath() {
    if (!summary?.workspacePath) return;
    try {
      await navigator.clipboard.writeText(summary.workspacePath);
    } catch {
      // ignore — clipboard unavailable
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {summary?.workspacePath && (
        <div style={pathBar} title={summary.workspacePath}>
          <span style={pathLabel}>Path</span>
          <span style={pathValue}>{summary.workspacePath}</span>
          <button
            onClick={copyPath}
            style={{
              background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
              borderRadius: 6, padding: '3px 10px', cursor: 'pointer',
              fontSize: '0.75rem', fontWeight: 500,
            }}
            title="Copy path"
          >
            Copy
          </button>
        </div>
      )}
      <div style={{ display: 'flex', borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0 }}>
        <button
          style={{ ...tabStyle, ...(tab === 'files' ? tabActive : {}) }}
          onClick={() => setTab('files')}
        >
          Files
        </button>
        <button
          style={{ ...tabStyle, ...(tab === 'subagents' ? tabActive : {}) }}
          onClick={() => setTab('subagents')}
        >
          Subagents
        </button>
      </div>
      {tab === 'subagents' ? (
        <SubagentPanel agentId={agentId} onChanged={() => setRefreshKey(k => k + 1)} />
      ) : (
        <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
          <WorkspaceFileTree
            agentId={agentId}
            selectedPath={selected}
            onSelect={p => setSelected(p || null)}
            refreshKey={refreshKey}
            onChange={() => setRefreshKey(k => k + 1)}
          />
          {isSubagentFile ? (
            <SubagentPanel agentId={agentId} onChanged={() => setRefreshKey(k => k + 1)} />
          ) : (
            <WorkspaceEditor
              agentId={agentId}
              path={selected}
              onSaved={() => setRefreshKey(k => k + 1)}
            />
          )}
        </div>
      )}
    </div>
  );
}
