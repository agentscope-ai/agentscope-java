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

export interface MemoryStore {
  id: string;
  ownerId?: string;
  name: string;
  description?: string;
  createdAt: number;
  updatedAt: number;
}

export interface Memory {
  id: string;
  storeId: string;
  path: string;
  content: string;
  headVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface MemoryVersion {
  memoryId: string;
  version: number;
  content: string;
  createdAt: number;
}

export interface CreateMemoryStoreRequest {
  name: string;
  description?: string;
}

export interface PutMemoryRequest {
  content: string;
}


export async function listMemoryStores(): Promise<MemoryStore[]> {
  const res = await fetch('/api/memory-stores', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list memory stores');
  return res.json();
}

export async function getMemoryStore(id: string): Promise<MemoryStore> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load memory store');
  return res.json();
}

export async function createMemoryStore(req: CreateMemoryStoreRequest): Promise<MemoryStore> {
  const res = await fetch('/api/memory-stores', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create memory store');
  return res.json();
}

export async function archiveMemoryStore(id: string): Promise<{ id: string; archivedAt: number }> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive memory store');
  return res.json();
}

export async function redactMemory(
  storeId: string,
  path: string,
  replacement?: string,
): Promise<Memory> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(storeId)}/redact`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ path, replacement }),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to redact memory');
  return res.json();
}

export async function deleteMemoryStore(id: string): Promise<void> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete memory store');
}

export async function listMemories(storeId: string): Promise<Memory[]> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to list memories');
  return res.json();
}

export async function getMemory(storeId: string, path: string): Promise<Memory> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to load memory');
  return res.json();
}

export async function putMemory(storeId: string, path: string, req: PutMemoryRequest): Promise<Memory> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { method: 'PUT', headers: authHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to save memory');
  return res.json();
}

export async function deleteMemory(storeId: string, path: string): Promise<void> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete memory');
}

export async function listMemoryVersions(storeId: string, path: string): Promise<MemoryVersion[]> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/versions/${encodePath(path)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to list memory versions');
  return res.json();
}

function encodePath(path: string): string {
  const normalized = path.startsWith('/') ? path.slice(1) : path;
  return normalized.split('/').map(encodeURIComponent).join('/');
}
