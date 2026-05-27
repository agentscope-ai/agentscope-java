import { getToken } from './auth';
import type { AgentDefinition } from './agents';

function authHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

export interface CloneAgentRequest {
  newAgentId?: string;
  name?: string;
}

export async function cloneAgent(
  sourceAgentId: string,
  req: CloneAgentRequest = {},
): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(sourceAgentId)}/clone`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Clone failed: ${res.status}`);
  }
  return res.json();
}
