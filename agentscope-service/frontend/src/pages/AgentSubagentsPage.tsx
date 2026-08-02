import React from 'react';
import { useOutletContext } from 'react-router-dom';
import SubagentPanel from '../components/SubagentPanel';
import LinkedWorkspaceBanner from '../components/LinkedWorkspaceBanner';
import type { AgentDefinition } from '../api/agents';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

export default function AgentSubagentsPage() {
  const { agentId, agent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null }>();
  const linked = agent?.workspaceId;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {linked ? (
        <LinkedWorkspaceBanner workspaceId={linked} resource="subagents" />
      ) : (
        <div style={helpStyle}>
          Subagents are stored as <code>subagents/&lt;name&gt;.md</code> with YAML frontmatter. Link a
          Workspace in Settings to share them across agents.
        </div>
      )}
      <div style={{ flex: 1, minHeight: 0 }}>
        <SubagentPanel agentId={agentId} readOnly={!!linked} />
      </div>
    </div>
  );
}
