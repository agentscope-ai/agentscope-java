import { authHeaders, readApiError } from './http';

export interface ManagedFile {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  createdAt: number;
}

export interface CreateFileRequest {
  filename: string;
  content: string;
  contentType?: string;
}


export async function listFiles(): Promise<ManagedFile[]> {
  const res = await fetch('/api/files', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list files');
  return res.json();
}

export async function createFile(req: CreateFileRequest): Promise<ManagedFile> {
  const res = await fetch('/api/files', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create file');
  return res.json();
}

export async function getFile(id: string): Promise<ManagedFile> {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to get file');
  return res.json();
}

export async function deleteFile(id: string): Promise<void> {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete file');
}
