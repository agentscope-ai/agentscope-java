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

import type { AccessGroup, ResourcePolicy, AccessRequest } from './resourceAccess';
import { api } from '@/lib/apiClient';
import type { NamespaceSummary } from '@/lib/namespaceScope';
export type Namespace = { tenant: string; name: string; displayName: string; kind: 'personal' | 'shared' | 'global'; owner: string; members: Record<string, string[]>; version: number; archived: boolean; groups?: Record<string, AccessGroup>; resources?: Record<string, ResourcePolicy>; requests?: AccessRequest[] };
export type ManagedNamespace = NamespaceSummary & { owner: string; archived: boolean; version: number; memberCount: number; canManage: boolean };
export type DirectoryAccount = { userId: string; username: string; displayName: string; disabled: boolean };
export type NamespaceAudit = { id: number; name: string; tenant: string; actor: string; namespace: Namespace; version: number; createdAt: string };
export const listManagedNamespaces = () => api.get<{ items: ManagedNamespace[] }>('/api/v1/namespaces');
export type IssueAccess = { mode: 'private' | 'shared' | 'namespace'; members?: Record<string, 'reader' | 'contributor'> };
export const listNamespaces = () => api.get<{ items: NamespaceSummary[] }>('/api/v1/me/namespaces');
export const createNamespace = (name: string, displayName: string, owner?: string, members: Record<string, string[]> = {}) => api.post<{ namespace: Namespace }>('/api/v1/namespaces', { name, displayName, owner, members });
export const getNamespace = (name: string) => api.get<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(name)}`);
export const updateNamespace = (n: Namespace) => api.put<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(n.name)}`, { members: n.members, displayName: n.displayName, version: n.version });
export const setNamespaceArchived = (n: Namespace, archived: boolean) => api.put<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(n.name)}`, { archived, version: n.version });
export const transferNamespace = (n: Namespace, owner: string) => api.post<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(n.name)}/transfer`, { owner, version: n.version });
export const searchAccounts = (namespace: string | undefined, q = '', ids: string[] = []) => {
  const path = namespace ? `/api/v1/namespaces/${encodeURIComponent(namespace)}/accounts` : '/api/v1/access/accounts';
  return api.get<{ items: DirectoryAccount[] }>(`${path}?${new URLSearchParams({ q, ids: ids.join(',') })}`);
};
export const listAccountNamespaces = (id: string) => api.get<{ items: (NamespaceSummary & { owner: string; version: number; archived: boolean })[] }>(`/api/v1/access/users/${encodeURIComponent(id)}/namespaces`);
export const listNamespaceAudit = (name?: string, offset = 0) => api.get<{ items: NamespaceAudit[] }>(`${name ? `/api/v1/namespaces/${encodeURIComponent(name)}/audit` : '/api/v1/access/audit'}?offset=${offset}`);
export const getMyPreferences = () => api.get<{ preferences: { defaultNamespace?: string } }>('/api/v1/me/preferences');
export const setDefaultNamespace = (defaultNamespace: string) => api.put('/api/v1/me/preferences', { defaultNamespace });
export const updateIssueAccess = (id: string, version: number, access: IssueAccess) => api.put(`/api/v1/issues/${encodeURIComponent(id)}/access`, { version, access });
