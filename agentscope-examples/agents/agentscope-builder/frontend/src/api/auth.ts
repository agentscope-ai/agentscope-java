const BASE = '';

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  username: string;
  roles: string[];
  aiAvailable?: boolean;
  isAdmin: boolean;
}

export interface UserProfile {
  userId: string;
  username: string;
  roles: string[];
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('claw_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('Invalid credentials');
  return res.json();
}

export async function me(): Promise<MeResponse> {
  const res = await fetch(`${BASE}/api/auth/me`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Unauthorized');
  return res.json();
}

export async function getProfile(): Promise<UserProfile> {
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
    const msg = await res.text().catch(() => '');
    throw new Error(msg || 'Failed to change password');
  }
}

export function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

export function saveToken(token: string) {
  localStorage.setItem('claw_token', token);
}

export function clearToken() {
  localStorage.removeItem('claw_token');
}

export function decodeJwt(token: string): Record<string, unknown> {
  try {
    return JSON.parse(atob(token.split('.')[1]));
  } catch {
    return {};
  }
}

export function getUsername(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.username as string) || (p.sub as string) || '';
}

export function getUserId(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.sub as string) || (p.userId as string) || '';
}

export function getRoles(): string[] {
  const token = getToken();
  if (!token) return [];
  const p = decodeJwt(token);
  return Array.isArray(p.roles) ? (p.roles as string[]) : [];
}

export function isAdmin(): boolean {
  return getRoles().some(r => r.toLowerCase() === 'admin');
}
