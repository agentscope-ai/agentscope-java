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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

export interface Marketplace {
  id: string;
  name: string;
  type: 'git' | 'nacos' | string;
  config?: Record<string, unknown>;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface MarketplaceSkill {
  name: string;
  dirName?: string;
  description?: string;
  type?: string;
}

export async function listMarketplaces(): Promise<Marketplace[]> {
  const res = await fetch('/api/marketplaces', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to list marketplaces');
  return res.json();
}

export async function createMarketplace(body: {
  name: string;
  type: 'git' | 'nacos';
  config?: Record<string, unknown>;
}): Promise<Marketplace> {
  const res = await fetch('/api/marketplaces', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function deleteMarketplace(id: string): Promise<void> {
  const res = await fetch(`/api/marketplaces/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error(await res.text());
}

export async function browseMarketplaceSkills(id: string): Promise<MarketplaceSkill[]> {
  const res = await fetch(`/api/marketplaces/${encodeURIComponent(id)}/skills`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function installMarketplaceSkill(
  workspaceId: string,
  marketplaceId: string,
  skillName: string,
  version?: string,
): Promise<void> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/skills/marketplace-install`,
    {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ marketplaceId, skillName, version }),
    },
  );
  if (!res.ok) throw new Error(await res.text());
}
