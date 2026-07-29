import { getToken } from './auth';
import type { AgentShareGrant, GranteeType, ShareTier } from './agents';

function authHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

async function asError(res: Response): Promise<never> {
  const msg = await res.text().catch(() => `${res.status}`);
  throw new Error(msg || `${res.status}`);
}

export async function listShares(agentId: string): Promise<AgentShareGrant[]> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/shares`, {
    headers: authHeaders(),
  });
  if (!res.ok) return asError(res);
  return res.json();
}

export interface AddShareRequest {
  granteeType: GranteeType;
  granteeId: string | null;
  tier: ShareTier;
}

export async function addShare(agentId: string, req: AddShareRequest): Promise<AgentShareGrant[]> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/shares`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) return asError(res);
  return res.json();
}

export async function revokeShare(
  agentId: string,
  granteeType: GranteeType,
  granteeId: string,
): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/shares/${encodeURIComponent(
      granteeType,
    )}/${encodeURIComponent(granteeId)}`,
    {
      method: 'DELETE',
      headers: authHeaders(),
    },
  );
  if (!res.ok && res.status !== 204) return asError(res);
}
