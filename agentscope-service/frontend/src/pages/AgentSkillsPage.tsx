import React, { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import SkillsWorkspacePanel from '../components/SkillsWorkspacePanel';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

export default function AgentSkillsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={helpStyle}>
        Manage skills under this agent's <code>workspace/skills/</code>. Marketplace install has
        been removed from the control plane; add skill folders directly in the workspace.
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        <SkillsWorkspacePanel
          agentId={agentId}
          refreshKey={refreshKey}
          onChange={() => setRefreshKey(k => k + 1)}
        />
      </div>
    </div>
  );
}
