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
