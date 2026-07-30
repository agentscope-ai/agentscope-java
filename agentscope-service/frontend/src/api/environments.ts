import { authHeaders, readApiError } from './http';

export interface Environment {
  id: string;
  name: string;
  type: string;
  config?: Record<string, unknown>;
  ownerId?: string;
  archivedAt?: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface CreateEnvironmentRequest {
  name: string;
  type: string;
  config?: Record<string, unknown>;
}


export async function listEnvironments(): Promise<Environment[]> {
  const res = await fetch('/api/environments', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list environments');
  return res.json();
}

export async function getEnvironment(id: string): Promise<Environment> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load environment');
  return res.json();
}

export async function createEnvironment(req: CreateEnvironmentRequest): Promise<Environment> {
  const res = await fetch('/api/environments', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create environment');
  return res.json();
}

export interface UpdateEnvironmentRequest {
  name?: string;
  config?: Record<string, unknown>;
}

export async function updateEnvironment(
  id: string,
  req: UpdateEnvironmentRequest,
): Promise<Environment> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update environment');
  return res.json();
}

export async function archiveEnvironment(id: string): Promise<Environment> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive environment');
  return res.json();
}

export async function deleteEnvironment(id: string): Promise<void> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete environment');
}

/** Returns the first active environment, or creates a default local one. */
export async function ensureDefaultEnvironment(): Promise<Environment> {
  const list = await listEnvironments();
  const active = list.find(e => !e.archivedAt);
  if (active) return active;
  return createEnvironment({ name: 'default-local', type: 'local' });
}
