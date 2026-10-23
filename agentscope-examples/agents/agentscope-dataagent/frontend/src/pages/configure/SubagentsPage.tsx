import React from 'react';
import { ACTIVE_AGENT_ID } from '../../api/activeAgent';
import BackToChatHeader from '../../components/BackToChatHeader';
import SubagentPanel from '../../components/SubagentPanel';

const helpBarStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

export default function SubagentsPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="Subagents" subtitle="Agents this one can delegate to" />
      <div style={helpBarStyle}>
        Subagents this agent can delegate to. Stored under <code>workspace/subagents/</code>.
        Add one by hand or pull from another existing agent.
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        <SubagentPanel agentId={ACTIVE_AGENT_ID} />
      </div>
    </div>
  );
}
