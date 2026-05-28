import { getToken } from './auth';

export type ContributionStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface Contribution {
  id: number;
  status: string;
  sourceUserId: string;
  sourceAgentId: string | null;
  targetType: string;
  targetPath: string;
  rationale: string | null;
  reviewerUserId: string | null;
  reviewerNote: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface SubmitRequest {
  sourceAgentId?: string | null;
  targetType: string;
  targetPath: string;
  rationale?: string | null;
  payload: string;
}

function authHeaders(): Record<string, string> {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

export async function submitContribution(req: SubmitRequest): Promise<Contribution> {
  const res = await fetch('/api/me/contributions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(`submitContribution failed: ${res.status}`);
  return res.json();
}

export async function listMyContributions(): Promise<Contribution[]> {
  const res = await fetch('/api/me/contributions', { headers: authHeaders() });
  if (!res.ok) throw new Error(`listMyContributions failed: ${res.status}`);
  return res.json();
}

export async function listContributions(status?: ContributionStatus): Promise<Contribution[]> {
  const url = '/api/admin/contributions' + (status ? `?status=${status}` : '');
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) throw new Error(`listContributions failed: ${res.status}`);
  return res.json();
}

export async function approveContribution(id: number, note: string): Promise<Contribution> {
  const res = await fetch(`/api/admin/contributions/${id}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ note }),
  });
  if (!res.ok) throw new Error(`approveContribution failed: ${res.status}`);
  return res.json();
}

export async function rejectContribution(id: number, note: string): Promise<Contribution> {
  const res = await fetch(`/api/admin/contributions/${id}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ note }),
  });
  if (!res.ok) throw new Error(`rejectContribution failed: ${res.status}`);
  return res.json();
}
