import { api } from '@/lib/apiClient';
import type { NamespaceSummary } from '@/lib/namespaceScope';
export type Namespace = { tenant: string; name: string; displayName: string; kind: 'personal' | 'shared'; owner: string; members: Record<string, string[]>; version: number };
export type IssueAccess = { mode: 'private' | 'shared' | 'namespace'; members?: Record<string, 'reader' | 'contributor'> };
export const listNamespaces = () => api.get<{ items: NamespaceSummary[] }>('/api/v1/me/namespaces');
export const createNamespace = (name: string, displayName: string) => api.post<{ namespace: Namespace }>('/api/v1/namespaces', { name, displayName });
export const getNamespace = (name: string) => api.get<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(name)}`);
export const updateNamespace = (n: Namespace) => api.put<{ namespace: Namespace }>(`/api/v1/namespaces/${encodeURIComponent(n.name)}`, { members: n.members, displayName: n.displayName, version: n.version });
export const updateIssueAccess = (id: string, version: number, access: IssueAccess) => api.put(`/api/v1/issues/${encodeURIComponent(id)}/access`, { version, access });
