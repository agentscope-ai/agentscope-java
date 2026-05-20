import { getToken } from './auth';

export type ShareTier = 'CLONE' | 'RUN' | 'EDIT';
export type GranteeType = 'USER' | 'WORKSPACE';

export interface AgentShareGrant {
  granteeType: GranteeType;
  granteeId: string;
  tier: ShareTier;
  createdAt: number;
  createdBy: string;
}

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
  shares?: AgentShareGrant[];
  runAs?: string;
  forkOf?: string;
  tierForCurrentUser?: ShareTier;
}

export interface AgentDraft {
  name: string;
  description?: string;
  sysPrompt?: string;
  suggestedTools?: string[];
  suggestedSkills?: { name: string; content: string }[];
  suggestedSubagents?: { name: string; content: string }[];
}

export interface AgentCreateRequest {
  id?: string;
  name: string;
  description?: string;
  sysPrompt?: string;
  maxIters?: number;
  templateId?: string;
  aiDraft?: AgentDraft;
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

export async function getAgent(id: string): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to load agent: ${res.status}`);
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
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
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
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) {
    throw new Error(`Failed to delete agent: ${res.status}`);
  }
}

export async function draftAgentWithAi(description: string): Promise<AgentDraft> {
  const res = await fetch('/api/agents/draft', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ description }),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to draft agent: ${res.status}`);
  }
  return res.json();
}
