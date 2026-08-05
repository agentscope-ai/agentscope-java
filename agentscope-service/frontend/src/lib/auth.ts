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
