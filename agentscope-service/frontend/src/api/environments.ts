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
