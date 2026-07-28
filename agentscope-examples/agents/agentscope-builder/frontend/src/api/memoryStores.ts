import { getToken } from './auth';

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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export async function listMemoryStores(): Promise<MemoryStore[]> {
  const res = await fetch('/api/memory-stores', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list memory stores: ${res.status}`);
  return res.json();
}

export async function getMemoryStore(id: string): Promise<MemoryStore> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to load memory store: ${res.status}`);
  return res.json();
}

export async function createMemoryStore(req: CreateMemoryStoreRequest): Promise<MemoryStore> {
  const res = await fetch('/api/memory-stores', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to create memory store: ${res.status}`);
  }
  return res.json();
}

export async function deleteMemoryStore(id: string): Promise<void> {
  const res = await fetch(`/api/memory-stores/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete memory store: ${res.status}`);
}

export async function listMemories(storeId: string): Promise<Memory[]> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error(`Failed to list memories: ${res.status}`);
  return res.json();
}

export async function getMemory(storeId: string, path: string): Promise<Memory> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error(`Failed to load memory: ${res.status}`);
  return res.json();
}

export async function putMemory(storeId: string, path: string, req: PutMemoryRequest): Promise<Memory> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { method: 'PUT', headers: authHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) throw new Error(`Failed to save memory: ${res.status}`);
  return res.json();
}

export async function deleteMemory(storeId: string, path: string): Promise<void> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/${encodePath(path)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete memory: ${res.status}`);
}

export async function listMemoryVersions(storeId: string, path: string): Promise<MemoryVersion[]> {
  const res = await fetch(
    `/api/memory-stores/${encodeURIComponent(storeId)}/memories/versions/${encodePath(path)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error(`Failed to list memory versions: ${res.status}`);
  return res.json();
}

function encodePath(path: string): string {
  const normalized = path.startsWith('/') ? path.slice(1) : path;
  return normalized.split('/').map(encodeURIComponent).join('/');
}
