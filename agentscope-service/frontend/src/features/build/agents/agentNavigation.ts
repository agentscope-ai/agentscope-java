import type { AgentDefinition } from '@/api/agents';

export type AgentDetailTabId =
  | 'overview'
  | 'sessions'
  | 'runtime'
  | 'entrypoints'
  | 'related-work'
  | 'settings';

export interface AgentDetailTab {
  id: AgentDetailTabId;
  label: string;
}

export function agentDetailTabs(runtimeKind?: string): AgentDetailTab[] {
  const tabs: AgentDetailTab[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'sessions', label: 'Sessions' },
    { id: 'runtime', label: runtimeKind === 'external-application' ? 'Integration' : 'Runtime' },
    { id: 'entrypoints', label: 'Published APIs' },
    { id: 'related-work', label: 'Activity' },
  ];
  if (runtimeKind === 'managed') tabs.push({ id: 'settings', label: 'Settings' });
  return tabs;
}

export function resolveAgentDetailTab(
  requestedTab: string | null,
  runtimeKind?: string,
): AgentDetailTabId {
  if (requestedTab === 'settings' && runtimeKind !== 'managed') return 'runtime';
  return agentDetailTabs(runtimeKind).some(tab => tab.id === requestedTab)
    ? requestedTab as AgentDetailTabId
    : 'overview';
}

export function agentDetailPath(agent: Pick<AgentDefinition, 'id' | 'runtimeKind'>): string {
  const id = encodeURIComponent(agent.id);
  return agent.runtimeKind === 'managed'
    ? `/agent-center/agents/${id}/manage/settings`
    : `/agent-center/agents/${id}`;
}

export function agentRuntimePath(agentId: string): string {
  return `/agent-center/agents/${encodeURIComponent(agentId)}?tab=runtime`;
}
