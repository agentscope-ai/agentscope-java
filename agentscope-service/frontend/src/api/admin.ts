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

import { getToken } from './auth';

export interface AdminUserView {
  userId: string;
  username: string;
  roles: string[];
}

export interface CreateUserRequest {
  username: string;
  initialPassword?: string;
  roles?: string[];
}

export interface CreateUserResponse {
  user: AdminUserView;
  generatedPassword?: string;
}

function authHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

async function asError(res: Response): Promise<never> {
  const msg = await res.text().catch(() => `${res.status}`);
  throw new Error(msg || `${res.status}`);
}

export async function listUsers(): Promise<AdminUserView[]> {
  const res = await fetch('/api/admin/users', { headers: authHeaders() });
  if (!res.ok) return asError(res);
  return res.json();
}

export async function createUser(req: CreateUserRequest): Promise<CreateUserResponse> {
  const res = await fetch('/api/admin/users', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) return asError(res);
  return res.json();
}

export async function resetPassword(userId: string, newPassword: string): Promise<AdminUserView> {
  const res = await fetch(`/api/admin/users/${encodeURIComponent(userId)}/password`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ newPassword }),
  });
  if (!res.ok) return asError(res);
  return res.json();
}

export async function updateRoles(userId: string, roles: string[]): Promise<AdminUserView> {
  const res = await fetch(`/api/admin/users/${encodeURIComponent(userId)}/roles`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ roles }),
  });
  if (!res.ok) return asError(res);
  return res.json();
}

export async function deleteUser(userId: string): Promise<void> {
  const res = await fetch(`/api/admin/users/${encodeURIComponent(userId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) return asError(res);
}
