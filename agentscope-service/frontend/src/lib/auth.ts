import { api, clearToken, getToken, saveToken } from './apiClient';

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

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await api.post<LoginResponse>('/api/auth/login', { username, password });
  saveToken(res.token);
  return res;
}

export async function me(): Promise<MeResponse> {
  return api.get<MeResponse>('/api/auth/me');
}

export function logout() {
  clearToken();
}

export { getToken, saveToken, clearToken };

export function getUsername(): string {
  try {
    const token = getToken();
    if (!token) return '';
    const payload = JSON.parse(atob(token.split('.')[1]));
    return String(payload.username || payload.sub || '');
  } catch {
    return '';
  }
}

export function isAdmin(): boolean {
  try {
    const token = getToken();
    if (!token) return false;
    const payload = JSON.parse(atob(token.split('.')[1]));
    const roles: string[] = payload.roles || [];
    return roles.map((r) => r.toLowerCase()).includes('admin');
  } catch {
    return false;
  }
}
