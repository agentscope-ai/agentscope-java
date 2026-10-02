import React from 'react';
import { useOutletContext } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';
import AgentSettingsForm from '../components/AgentSettingsForm';

export default function AgentSettingsPage() {
  const { agent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null }>();
  if (!agent) {
    return <div style={{ padding: '24px 28px', color: '#64748b' }}>Loading…</div>;
  }
  return <AgentSettingsForm agent={agent} />;
}
