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
