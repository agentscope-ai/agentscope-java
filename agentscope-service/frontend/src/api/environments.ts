import { getToken } from './auth';

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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export async function listEnvironments(): Promise<Environment[]> {
  const res = await fetch('/api/environments', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list environments: ${res.status}`);
  return res.json();
}

export async function getEnvironment(id: string): Promise<Environment> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to load environment: ${res.status}`);
  return res.json();
}

export async function createEnvironment(req: CreateEnvironmentRequest): Promise<Environment> {
  const res = await fetch('/api/environments', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to create environment: ${res.status}`);
  }
  return res.json();
}

export async function archiveEnvironment(id: string): Promise<Environment> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`Failed to archive environment: ${res.status}`);
  return res.json();
}

export async function deleteEnvironment(id: string): Promise<void> {
  const res = await fetch(`/api/environments/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete environment: ${res.status}`);
}

/** Returns the first active environment, or creates a default local one. */
export async function ensureDefaultEnvironment(): Promise<Environment> {
  const list = await listEnvironments();
  const active = list.find(e => !e.archivedAt);
  if (active) return active;
  return createEnvironment({ name: 'default-local', type: 'local' });
}
