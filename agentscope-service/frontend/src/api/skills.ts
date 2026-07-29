import { getToken } from './auth';

export type SkillOrigin = 'custom' | 'marketplace';

export interface SkillMarketplaceMeta {
  repoType: string;
  repoLocation: string;
  originalName: string;
  installedAt: string;
}

export interface WorkspaceSkillInfo {
  dirName: string;
  name: string;
  description?: string | null;
  sizeBytes: number;
  resourceCount: number;
  hasReferences: boolean;
  hasScripts: boolean;
  origin: SkillOrigin;
  marketplace?: SkillMarketplaceMeta;
}

export interface WorkspaceSkillDetail {
  name: string;
  description?: string | null;
  markdown: string;
  resources: Record<string, string>;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/skills`;
}

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

export async function listWorkspaceSkills(agentId: string): Promise<WorkspaceSkillInfo[]> {
  const res = await fetch(`${base(agentId)}/workspace`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to list workspace skills');
  return res.json();
}

export async function getWorkspaceSkill(
  agentId: string,
  name: string,
): Promise<WorkspaceSkillDetail> {
  const res = await fetch(`${base(agentId)}/workspace/${encodeURIComponent(name)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw await readError(res, 'Failed to load workspace skill');
  return res.json();
}

export async function upsertWorkspaceSkill(
  agentId: string,
  name: string,
  markdown: string,
  resources?: Record<string, string>,
): Promise<WorkspaceSkillInfo> {
  const res = await fetch(`${base(agentId)}/workspace/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ markdown, resources }),
  });
  if (!res.ok) throw await readError(res, 'Failed to save workspace skill');
  return res.json();
}

export async function deleteWorkspaceSkill(agentId: string, name: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/workspace/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readError(res, 'Failed to delete skill');
}

export interface InstallResult {
  status: 'installed' | 'conflict';
  conflictName?: string;
  installed?: WorkspaceSkillInfo;
}

export interface MarketplaceInstallRequest {
  marketplaceId: string;
  skillName: string;
  targetName?: string;
  overwrite?: boolean;
}

export async function installFromMarketplace(
  agentId: string,
  req: MarketplaceInstallRequest,
): Promise<InstallResult> {
  const res = await fetch(`${base(agentId)}/workspace/marketplace-install`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (res.status === 409) {
    return { status: 'conflict', conflictName: req.targetName ?? req.skillName };
  }
  if (!res.ok) throw await readError(res, 'Failed to install skill');
  const installed = (await res.json()) as WorkspaceSkillInfo;
  return { status: 'installed', installed };
}
