/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
