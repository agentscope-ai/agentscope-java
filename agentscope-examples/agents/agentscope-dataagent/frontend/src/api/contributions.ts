import { getToken } from './auth';

export type ContributionStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type ContributionTargetType =
  | 'skill'
  | 'subagent'
  | 'memory'
  | 'agents_md'
  | 'knowledge';

export interface FileEntry {
  relPath: string;
  content: string;
}

export interface Contribution {
  id: number;
  status: string;
  sourceUserId: string;
  sourceAgentId: string | null;
  targetAgentId: string | null;
  targetType: string;
  targetPath: string;
  rationale: string | null;
  reviewerUserId: string | null;
  reviewerNote: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface ContributionDetail {
  contribution: Contribution;
  payload: FileEntry[];
  approvedPayload: FileEntry[] | null;
}

export interface SubmitRequest {
  sourceAgentId: string;
  targetAgentId?: string | null;
  targetType: ContributionTargetType | string;
  targetPath: string;
  rationale?: string | null;
  payload: FileEntry[];
}

export interface SubmitFromWorkspaceRequest {
  sourceAgentId: string;
  targetAgentId?: string | null;
  targetType: ContributionTargetType | string;
  targetPath: string;
  rationale?: string | null;
  sourcePaths: string[];
}

export interface ApproveRequest {
  note?: string | null;
  approvedPayload?: FileEntry[] | null;
}

function authHeaders(): Record<string, string> {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { 'Content-Type': 'application/json', ...authHeaders() };
}

async function unwrap<T>(res: Response, ctx: string): Promise<T> {
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `${ctx} failed: ${res.status}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

export async function submitContribution(req: SubmitRequest): Promise<Contribution> {
  const res = await fetch('/api/me/contributions', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  return unwrap<Contribution>(res, 'submitContribution');
}

export async function submitFromWorkspace(
  req: SubmitFromWorkspaceRequest,
): Promise<Contribution> {
  const res = await fetch('/api/me/contributions/from-workspace', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  return unwrap<Contribution>(res, 'submitFromWorkspace');
}

export async function listMyContributions(): Promise<Contribution[]> {
  const res = await fetch('/api/me/contributions', { headers: authHeaders() });
  return unwrap<Contribution[]>(res, 'listMyContributions');
}

export async function listContributions(status?: ContributionStatus): Promise<Contribution[]> {
  const url = '/api/admin/contributions' + (status ? `?status=${status}` : '');
  const res = await fetch(url, { headers: authHeaders() });
  return unwrap<Contribution[]>(res, 'listContributions');
}

export async function getContribution(id: number): Promise<ContributionDetail> {
  const res = await fetch(`/api/admin/contributions/${id}`, { headers: authHeaders() });
  return unwrap<ContributionDetail>(res, 'getContribution');
}

export async function approveContribution(
  id: number,
  req: ApproveRequest,
): Promise<Contribution> {
  const res = await fetch(`/api/admin/contributions/${id}/approve`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  return unwrap<Contribution>(res, 'approveContribution');
}

export async function rejectContribution(id: number, note: string): Promise<Contribution> {
  const res = await fetch(`/api/admin/contributions/${id}/reject`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ note }),
  });
  return unwrap<Contribution>(res, 'rejectContribution');
}
