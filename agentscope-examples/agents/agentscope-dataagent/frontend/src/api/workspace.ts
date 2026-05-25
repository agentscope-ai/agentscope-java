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

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

export async function summary(agentId: string): Promise<WorkspaceSummary> {
  const res = await fetch(base(agentId), { headers: authHeaders() });
  return unwrap<WorkspaceSummary>(res);
}

export async function tree(agentId: string, recursive = true): Promise<FileNode[]> {
  const res = await fetch(`${base(agentId)}/files?recursive=${recursive}`, {
    headers: authHeaders(),
  });
  return unwrap<FileNode[]>(res);
}

export async function readFile(agentId: string, path: string): Promise<string> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  return res.text();
}

export async function writeFile(agentId: string, path: string, content: string): Promise<FileNode> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ content }),
  });
  return unwrap<FileNode>(res);
}

export async function createFile(agentId: string, path: string): Promise<FileNode> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}&type=file`, {
    method: 'POST',
    headers: authHeaders(),
  });
  return unwrap<FileNode>(res);
}

export async function createDir(agentId: string, path: string): Promise<FileNode> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}&type=dir`, {
    method: 'POST',
    headers: authHeaders(),
  });
  return unwrap<FileNode>(res);
}

export async function moveNode(agentId: string, from: string, to: string): Promise<FileNode> {
  const res = await fetch(`${base(agentId)}/file/move`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ from, to }),
  });
  return unwrap<FileNode>(res);
}

export async function deleteNode(agentId: string, path: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  await unwrap<void>(res);
}

export async function uploadFile(agentId: string, file: File, dir = 'knowledge'): Promise<FileNode> {
  const form = new FormData();
  form.append('file', file, file.name);
  const q = new URLSearchParams({ path: dir });
  const res = await fetch(`${base(agentId)}/upload?${q.toString()}`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  return unwrap<FileNode>(res);
}
