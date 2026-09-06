import type { AgentDefinition } from '@/api/agents';

export function buildInitialTeamMembers(agentIds: string[], agents: AgentDefinition[]) {
  const usedRoles = new Set<string>();
  return agentIds.map((agentId, index) => {
    const agent = agents.find(item => item.id === agentId);
    const source = agent?.agentKey || agent?.name || `member-${index + 1}`;
    const normalized = source
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '') || `member-${index + 1}`;
    const base = normalized === 'leader' ? 'member' : normalized;
    let role = base;
    let suffix = 2;
    while (usedRoles.has(role)) role = `${base}-${suffix++}`;
    usedRoles.add(role);
    return { agentId, role };
  });
}
