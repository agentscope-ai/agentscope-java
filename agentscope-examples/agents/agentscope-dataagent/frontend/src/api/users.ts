const BASE = '';

export interface UserView {
  userId: string;
  username: string;
  roles: string[];
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('claw_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function listUsers(): Promise<UserView[]> {
  const res = await fetch(`${BASE}/api/admin/users`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to list users');
  return res.json();
}

export async function createUser(data: {
  userId?: string;
  username: string;
  password: string;
  roles?: string[];
}): Promise<UserView> {
  const res = await fetch(`${BASE}/api/admin/users`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const msg = await res.text();
    throw new Error(msg || 'Failed to create user');
  }
  return res.json();
}

export async function updateUserRoles(userId: string, roles: string[]): Promise<UserView> {
  const res = await fetch(`${BASE}/api/admin/users/${encodeURIComponent(userId)}/roles`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ roles }),
  });
  if (!res.ok) throw new Error('Failed to update roles');
  return res.json();
}

export async function deleteUser(userId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/admin/users/${encodeURIComponent(userId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to delete user');
}

export async function adminResetPassword(userId: string, newPassword: string): Promise<void> {
  const res = await fetch(`${BASE}/api/admin/users/${encodeURIComponent(userId)}/password`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPassword }),
  });
  if (!res.ok) throw new Error('Failed to reset password');
}

export async function getProfile(): Promise<UserView> {
  const res = await fetch(`${BASE}/api/user/profile`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to fetch profile');
  return res.json();
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  const res = await fetch(`${BASE}/api/user/change-password`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
  if (!res.ok) {
    const msg = await res.text();
    throw new Error(msg || 'Failed to change password');
  }
}
