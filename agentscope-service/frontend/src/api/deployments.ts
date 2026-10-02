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

import { authHeaders, readApiError } from './http';

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
  lastHandsStats?: { acquires: number; releases: number; timeouts: number } | null;
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


export async function listDeployments(): Promise<Deployment[]> {
  const res = await fetch('/api/deployments', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list deployments');
  return res.json();
}

export async function getDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load deployment');
  return res.json();
}

export async function createDeployment(req: CreateDeploymentRequest): Promise<Deployment> {
  const res = await fetch('/api/deployments', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create deployment');
  return res.json();
}

export async function updateDeployment(id: string, req: UpdateDeploymentRequest): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update deployment');
  return res.json();
}

export async function archiveDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive deployment');
  return res.json();
}

export async function deleteDeployment(id: string): Promise<void> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete deployment');
}

export async function runDeployment(id: string, message?: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/run`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(message ? { text: message } : {}),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to run deployment');
  return res.json();
}

export async function pauseDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/pause`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to pause deployment');
  return res.json();
}

export async function unpauseDeployment(id: string): Promise<Deployment> {
  const res = await fetch(`/api/deployments/${encodeURIComponent(id)}/unpause`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to unpause deployment');
  return res.json();
}
