const BASE = '';

export interface TreeNode {
  name: string;
  path: string;
  kind: 'file' | 'dir';
  sizeBytes: number;
  modifiedMs: number;
  children: TreeNode[] | null;
}

export interface FileContent {
  path: string;
  sizeBytes: number;
  binary: boolean;
  text: string | null;
  base64: string | null;
}

export interface FileMeta {
  path: string;
  kind: 'file' | 'dir';
  sizeBytes: number;
  modifiedMs: number;
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('claw_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  // DELETE returns 204 — no body to parse.
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

export async function fetchTree(depth = 8): Promise<TreeNode> {
  const res = await fetch(`${BASE}/api/user/workspace/tree?depth=${depth}`, {
    headers: authHeaders(),
  });
  return unwrap<TreeNode>(res);
}

export async function readFile(path: string): Promise<FileContent> {
  const q = new URLSearchParams({ path });
  const res = await fetch(`${BASE}/api/user/workspace/file?${q.toString()}`, {
    headers: authHeaders(),
  });
  return unwrap<FileContent>(res);
}

export async function writeFile(path: string, content: string): Promise<FileMeta> {
  const q = new URLSearchParams({ path });
  const res = await fetch(`${BASE}/api/user/workspace/file?${q.toString()}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ content }),
  });
  return unwrap<FileMeta>(res);
}

export async function createFile(path: string, content = ''): Promise<FileMeta> {
  const q = new URLSearchParams({ path, kind: 'file' });
  const res = await fetch(`${BASE}/api/user/workspace/file?${q.toString()}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ content }),
  });
  return unwrap<FileMeta>(res);
}

export async function createDir(path: string): Promise<FileMeta> {
  const q = new URLSearchParams({ path, kind: 'dir' });
  const res = await fetch(`${BASE}/api/user/workspace/file?${q.toString()}`, {
    method: 'POST',
    headers: authHeaders(),
  });
  return unwrap<FileMeta>(res);
}

export async function deletePath(path: string): Promise<void> {
  const q = new URLSearchParams({ path });
  const res = await fetch(`${BASE}/api/user/workspace/file?${q.toString()}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  await unwrap<void>(res);
}

export async function uploadFile(file: File, dir = 'knowledge'): Promise<FileMeta> {
  const form = new FormData();
  form.append('file', file, file.name);
  const q = new URLSearchParams({ dir });
  const res = await fetch(`${BASE}/api/user/workspace/upload?${q.toString()}`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  return unwrap<FileMeta>(res);
}
