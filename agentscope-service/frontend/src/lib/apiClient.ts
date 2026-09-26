import { clearAccountIdentity } from "./accountIdentity";
import { namespaceHeaders } from "./namespaceScope";
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

const TOKEN_KEY = 'claw_token';

export class ApiError extends Error {
  status: number;
  body: string;

  constructor(status: number, body: string) {
    super(body || `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  clearAccountIdentity();
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}`, ...namespaceHeaders() } : {};
}

function scopedApiPath(path: string): string {
  if (typeof window === 'undefined' || !path.startsWith('/api/v1/')) return path;
  const current = new URLSearchParams(window.location.search);
  const scope = namespaceHeaders();
  const tenant = scope['X-AgentScope-Tenant'] || current.get('tenant');
  const namespace = scope['X-AgentScope-Namespace'] || current.get('namespace');
  if (!tenant && !namespace) return path;
  const url = new URL(path, window.location.origin);
  if (tenant && !url.searchParams.has('tenant')) url.searchParams.set('tenant', tenant);
  if (namespace && !url.searchParams.has('namespace')) url.searchParams.set('namespace', namespace);
  return `${url.pathname}${url.search}`;
}

export async function apiResponse(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const requestPath = scopedApiPath(path);
  const headers = new Headers(init.headers);
  const auth = authHeaders();
  Object.entries(auth).forEach(([k, v]) => headers.set(k, v));
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(requestPath, { ...init, headers });
  if (res.status === 401 && !requestPath.includes('/api/auth/login')) {
    clearToken();
    if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
      window.location.assign('/login');
    }
    throw new ApiError(401, 'Unauthorized');
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new ApiError(res.status, body || res.statusText);
  }
  return res;
}

export async function apiFetch<T = unknown>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const res = await apiResponse(path, init);
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return (await res.text()) as T;
}

export const api = {
  get: <T>(path: string) => apiFetch<T>(path),
  post: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) }),
};
