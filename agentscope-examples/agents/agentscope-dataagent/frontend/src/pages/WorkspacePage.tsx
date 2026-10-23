import React, { useEffect, useState } from 'react';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { summary as fetchSummary, WorkspaceSummary } from '../api/workspace';
import BackToChatHeader from '../components/BackToChatHeader';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';

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
const hint: React.CSSProperties = {
  padding: '5px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#fffbeb', color: '#92400e',
  fontSize: '0.74rem', flexShrink: 0,
};

export default function WorkspacePage() {
  const agentId = ACTIVE_AGENT_ID;
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [summary, setSummary] = useState<WorkspaceSummary | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchSummary(agentId)
      .then(s => { if (!cancelled) setSummary(s); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [agentId]);

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
      <BackToChatHeader title="Workspace" subtitle="Browse the agent's working directory" />
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
      <div style={hint}>
        Read-only view. Edit workspace contents from the Skills, Subagents, or Tools pages — or by talking to the agent.
      </div>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <WorkspaceFileTree
          agentId={agentId}
          selectedPath={selected}
          onSelect={p => setSelected(p || null)}
          refreshKey={refreshKey}
          onRefresh={() => setRefreshKey(k => k + 1)}
        />
        <WorkspaceEditor
          agentId={agentId}
          path={selected}
          refreshKey={refreshKey}
        />
      </div>
    </div>
  );
}
