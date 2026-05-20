import React, { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';

export default function AgentWorkspacePage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      <WorkspaceFileTree
        agentId={agentId}
        selectedPath={selected}
        onSelect={p => setSelected(p || null)}
        refreshKey={refreshKey}
        onChange={() => setRefreshKey(k => k + 1)}
      />
      <WorkspaceEditor
        agentId={agentId}
        path={selected}
        onSaved={() => setRefreshKey(k => k + 1)}
      />
    </div>
  );
}
