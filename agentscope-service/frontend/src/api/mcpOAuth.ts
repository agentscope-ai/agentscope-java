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

export interface McpOAuthSettings {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  clientId: string;
  clientSecret?: string;
  authMethod: 'none' | 'client_secret_basic' | 'client_secret_post';
  scope: string;
  resource?: string;
  issuer?: string;
  authorizationParams?: Record<string, string>;
}
export interface McpOAuthConnection {
  provider?: string;
  account?: GitHubAccountStatus;
  id: string;
  vaultId: string;
  serverName: string;
  endpoint: string;
  settings: McpOAuthSettings;
  hasClientSecret: boolean;
  connected: boolean;
  callbackUrl: string;
}
export interface McpOAuthFlow {
  flowId: string;
  authorizationUrl: string;
  expiresAt: number;
}
export interface McpOAuthStatus {
  status: 'pending' | 'exchanging' | 'authorized' | 'completed' | 'failed' | 'cancelled' | 'expired';
  errorCode?: string;
  expiresAt: number;
}
const path = (vault: string, connection?: string) => `/api/vaults/${encodeURIComponent(vault)}/oauth-connections${connection ? `/${encodeURIComponent(connection)}` : ''}`;
async function request<T>(url: string, method = 'GET', body?: unknown): Promise<T> {
  const res = await fetch(url, { method, headers: authHeaders(), body: body === undefined ? undefined : JSON.stringify(body) });
  if (!res.ok) throw await readApiError(res, 'OAuth request failed');
  return res.status === 204 ? undefined as T : res.json();
}
export const listMcpOAuth = (vault: string) => request<McpOAuthConnection[]>(path(vault));
export const saveMcpOAuth = (vault: string, body: McpOAuthSettings & { serverName: string; endpoint: string }, id?: string) => request<McpOAuthConnection>(path(vault, id), id ? 'PATCH' : 'POST', body);
export const startMcpOAuth = (c: McpOAuthConnection) => request<McpOAuthFlow>(`${path(c.vaultId, c.id)}/authorize`, 'POST');
export const disconnectMcpOAuth = (c: McpOAuthConnection) => request(`${path(c.vaultId, c.id)}/disconnect`, 'POST');
export const getMcpOAuthStatus = (c: McpOAuthConnection, flow: string) => request<McpOAuthStatus>(`${path(c.vaultId, c.id)}/flows/${encodeURIComponent(flow)}`);
export const completeMcpOAuth = (c: McpOAuthConnection, flow: string) => request<{ vaultId: string }>(`${path(c.vaultId, c.id)}/flows/${encodeURIComponent(flow)}/complete`, 'POST');
export const cancelMcpOAuth = (c: McpOAuthConnection, flow: string) => request<void>(`${path(c.vaultId, c.id)}/flows/${encodeURIComponent(flow)}/cancel`, 'POST');

export interface GitHubAccountStatus {
  login?: string;
  userId?: number;
  scope?: string;
  status: 'ready' | 'authorized' | 'verification_failed' | 'reauthorization_required';
  errorCode?: string;
  toolCount: number;
  checkedAt: number;
}
export interface GitHubApplication {
  enabled: boolean;
  clientId: string;
  hasClientSecret: boolean;
  scope: string;
  revision: number;
  callbackUrl: string;
}
export const isGitHubMcp = (url?: string) => url === 'https://api.githubcopilot.com/mcp/' || url === 'https://api.githubcopilot.com/mcp';
export const getGitHubProvider = () => request<{ configured: boolean; scope: string }>('/api/oauth/providers/github');
export const getGitHubApplication = () => request<GitHubApplication>('/api/admin/integrations/github');
export const saveGitHubApplication = (app: Pick<GitHubApplication, 'enabled' | 'clientId' | 'scope' | 'revision'> & { clientSecret?: string }) => request<GitHubApplication>('/api/admin/integrations/github', 'PUT', app);
export const createGitHubConnection = (vault: string, serverName: string, endpoint: string) => request<McpOAuthConnection>(path(vault), 'POST', { provider: 'github', serverName, endpoint });
export const verifyGitHubConnection = (c: McpOAuthConnection) => request<GitHubAccountStatus>(`${path(c.vaultId, c.id)}/verify`, 'POST');
