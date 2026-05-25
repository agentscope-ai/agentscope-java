import React, { useCallback, useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { AgentDefinition, getAgent } from '../api/agents';
import SessionsSidebar from './SessionsSidebar';
import { ShellOutletContext } from './EditTierGate';

export default function AppShell() {
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [agentLoading, setAgentLoading] = useState(true);
  const [agentError, setAgentError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setAgentLoading(true);
    setAgentError(null);
    getAgent(ACTIVE_AGENT_ID)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(e => { if (!cancelled) setAgentError(e instanceof Error ? e.message : 'Failed to load agent'); })
      .finally(() => { if (!cancelled) setAgentLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const bumpSidebar = useCallback(() => setRefreshTick(t => t + 1), []);

  const ctx: ShellOutletContext = { agent, agentLoading, agentError, bumpSidebar };

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' }}>
      <SessionsSidebar refreshKey={refreshTick} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <Outlet context={ctx} />
      </div>
    </div>
  );
}
