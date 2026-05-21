import React, { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';
import SubagentPanel from '../components/SubagentPanel';

type Tab = 'files' | 'subagents';

const tabStyle: React.CSSProperties = {
  padding: '8px 16px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  border: 'none', background: 'transparent', color: '#64748b', borderBottom: '2px solid transparent',
};
const tabActive: React.CSSProperties = { color: '#4338ca', borderBottomColor: '#4338ca' };

export default function AgentWorkspacePage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [tab, setTab] = useState<Tab>('files');

  const isSubagentFile = selected?.startsWith('subagents/') && selected.endsWith('.md');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
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
