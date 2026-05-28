import { getToken } from './auth';

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  size?: number;
  children?: FileNode[];
}

export interface WorkspaceSummary {
  agentId: string;
  workspacePath: string;
  exists: boolean;
  agentsMdExists: boolean;
  memoryMdExists: boolean;
  skillCount: number;
  subagentCount: number;
  dailyMemoryCount: number;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace`;
}

export async function summary(agentId: string): Promise<WorkspaceSummary> {
  const res = await fetch(base(agentId), { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to load workspace summary');
  return res.json();
}

export async function tree(agentId: string, recursive = true): Promise<FileNode[]> {
  const res = await fetch(`${base(agentId)}/files?recursive=${recursive}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to load workspace files');
  return res.json();
}

export async function readFile(agentId: string, path: string): Promise<string> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to read file');
  return res.text();
}

export async function writeFile(agentId: string, path: string, content: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ content }),
  });
  if (!res.ok) throw new Error('Failed to write file');
}

// Empty directories are not representable on the composite filesystem (intermediate dirs
// materialize implicitly on first file write), so only file creation is exposed here.
export async function createNode(agentId: string, path: string): Promise<void> {
  const res = await fetch(
    `${base(agentId)}/file?path=${encodeURIComponent(path)}&type=file`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('Failed to create file');
}

export async function moveNode(agentId: string, from: string, to: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/file/move`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ from, to }),
  });
  if (!res.ok) throw new Error('Failed to move file');
}

export async function deleteNode(agentId: string, path: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error('Failed to delete file');
}

export async function uploadFile(agentId: string, path: string, file: File): Promise<void> {
  const fd = new FormData();
  fd.append('file', file);
  const res = await fetch(`${base(agentId)}/upload?path=${encodeURIComponent(path)}`, {
    method: 'POST',
    headers: authHeaders(),
    body: fd,
  });
  if (!res.ok) throw new Error('Failed to upload file');
}
