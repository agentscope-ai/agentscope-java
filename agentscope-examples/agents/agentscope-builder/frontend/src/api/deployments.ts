import { getToken } from './auth';

export type TriggerType = 'cron' | 'webhook' | 'manual';

export interface Deployment {
  id: string;
  ownerId?: string;
  name: string;
  agentId: string;
  agentVersion?: number | null;
  environmentId: string;
  triggerType: TriggerType;
  cronExpression?: string | null;
  webhookToken?: string | null;
  enabled: boolean;
  lastRunAt?: number | null;
  lastSessionId?: string | null;
  lastStatus?: string | null;
  createdAt: number;
  updatedAt: number;
  archivedAt?: number | null;
}

export interface CreateDeploymentRequest {
  name: string;
  agentId: string;
  agentVersion?: number;
  environmentId?: string;
  triggerType: TriggerType;
  cronExpression?: string;
}

export interface UpdateDeploymentRequest {
  name?: string;
  enabled?: boolean;
  cronExpression?: string;
  environmentId?: string;
  agentVersion?: number;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export async function listDeployments(): Promise<Deployment[]> {
  const res = await fetch('/api/deployments', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list deployments: ${res.status}`);
  return res.json();
}

export async function getDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to load deployment: ${res.status}`);
  return res.json();
}

export async function createDeployment(req: CreateDeploymentRequest): Promise<Deployment> {
  const res = await fetch('/api/deployments', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to create deployment: ${res.status}`);
  }
  return res.json();
}

export async function updateDeployment(id: string, req: UpdateDeploymentRequest): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to update deployment: ${res.status}`);
  }
  return res.json();
}

export async function archiveDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`Failed to archive deployment: ${res.status}`);
  return res.json();
}

export async function deleteDeployment(id: string): Promise<void> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete deployment: ${res.status}`);
}

export async function runDeployment(id: string, message?: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/run`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(message ? { text: message } : {}),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to run deployment: ${res.status}`);
  }
  return res.json();
}
