import { getToken } from './auth';

export interface AgentDefinition {
  id: string;
  name: string;
  description?: string;
  sysPrompt?: string;
  maxIters?: number;
  tools?: string[];
  scope: 'global' | 'user';
  ownerId?: string;
  createdAt: number;
  updatedAt: number;
}

export interface AgentCreateRequest {
  id?: string;
  name: string;
  description?: string;
  sysPrompt?: string;
  maxIters?: number;
}

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

export async function listAgents(): Promise<AgentDefinition[]> {
  const res = await fetch('/api/agents', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list agents: ${res.status}`);
  return res.json();
}

export async function createAgent(req: AgentCreateRequest): Promise<AgentDefinition> {
  const res = await fetch('/api/agents', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`Failed to create agent: ${msg}`);
  }
  return res.json();
}

export async function updateAgent(
  id: string,
  req: AgentCreateRequest,
): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${id}`, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`Failed to update agent: ${msg}`);
  }
  return res.json();
}

export async function deleteAgent(id: string): Promise<void> {
  const res = await fetch(`/api/agents/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) {
    throw new Error(`Failed to delete agent: ${res.status}`);
  }
}
