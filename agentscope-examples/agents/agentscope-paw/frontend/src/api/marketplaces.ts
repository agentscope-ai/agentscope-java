export type MarketplaceType = 'git' | 'nacos';

export interface MarketplaceSummary {
  id: string;
  type: MarketplaceType | string;
  properties: Record<string, unknown>;
}

export interface MarketplaceWriteRequest {
  id: string;
  type: MarketplaceType | string;
  properties: Record<string, unknown>;
}

export interface TestConnectionResult {
  ok: boolean;
  message?: string | null;
  skillCount?: number | null;
}

export interface MarketSkillBrief {
  name: string;
  description?: string | null;
  version?: string | null;
}

export interface MarketSkillDetail {
  name: string;
  description?: string | null;
  markdown: string;
  resources: Record<string, string>;
}

const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;
const BASE = '/api/marketplaces';

async function readError(res: Response, fallback: string): Promise<Error> {
  try {
    const body = await res.json();
    if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
      return new Error(body.message);
    }
  } catch {
    // ignore
  }
  return new Error(`${fallback} (${res.status})`);
}

export async function listMarketplaces(): Promise<MarketplaceSummary[]> {
  const res = await fetch(BASE);
  if (!res.ok) throw await readError(res, 'Failed to list marketplaces');
  return res.json();
}

export async function createMarketplace(
  req: MarketplaceWriteRequest,
): Promise<MarketplaceSummary> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readError(res, 'Failed to create marketplace');
  return res.json();
}

export async function updateMarketplace(
  id: string,
  req: MarketplaceWriteRequest,
): Promise<MarketplaceSummary> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readError(res, 'Failed to update marketplace');
  return res.json();
}

export async function deleteMarketplace(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok && res.status !== 204) throw await readError(res, 'Failed to delete marketplace');
}

export async function testMarketplaceTransient(
  req: MarketplaceWriteRequest,
): Promise<TestConnectionResult> {
  const res = await fetch(`${BASE}/test`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readError(res, 'Failed to test marketplace');
  return res.json();
}

export async function testMarketplaceExisting(id: string): Promise<TestConnectionResult> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}/test`, { method: 'POST' });
  if (!res.ok) throw await readError(res, 'Failed to test marketplace');
  return res.json();
}

export async function listMarketplaceSkills(id: string): Promise<MarketSkillBrief[]> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}/skills`);
  if (!res.ok) throw await readError(res, 'Failed to list marketplace skills');
  return res.json();
}

export async function getMarketplaceSkill(
  id: string,
  name: string,
): Promise<MarketSkillDetail> {
  const res = await fetch(
    `${BASE}/${encodeURIComponent(id)}/skills/${encodeURIComponent(name)}`,
  );
  if (!res.ok) throw await readError(res, 'Failed to load marketplace skill');
  return res.json();
}
