import React from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';

export interface ShellOutletContext {
  agent: AgentDefinition | null;
  agentLoading: boolean;
  agentError: string | null;
  bumpSidebar: () => void;
}

/**
 * Route guard: only allows children to render when the current user has EDIT tier on the
 * active data agent. While the agent is still loading, shows a placeholder; on insufficient
 * tier, redirects back to /chat.
 */
export default function EditTierGate({ children }: { children: React.ReactElement }) {
  const ctx = useOutletContext<ShellOutletContext>();
  if (ctx.agentLoading) {
    return <div style={{ padding: 32, color: '#64748b' }}>Loading…</div>;
  }
  if (ctx.agentError) {
    return <div style={{ padding: 32, color: '#dc2626' }}>{ctx.agentError}</div>;
  }
  if (ctx.agent?.tierForCurrentUser !== 'EDIT') {
    return <Navigate to="/chat" replace />;
  }
  return children;
}
