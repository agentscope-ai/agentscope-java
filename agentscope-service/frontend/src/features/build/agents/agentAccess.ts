import type { AgentDefinition } from "@/api/agents";

export function canEditAgentDefinition(
  agent: AgentDefinition,
  username: string,
): boolean {
  if (agent.scope === "global") return false;
  if (agent.tierForCurrentUser != null)
    return agent.tierForCurrentUser === "EDIT";
  return !!username && agent.ownerId === username;
}
