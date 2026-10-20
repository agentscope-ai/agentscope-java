import { api } from '@/lib/apiClient';

export interface SessionTarget { type: 'agent' | 'team' | 'workflow'; id: string; version?: number; revisionId?: string }
export interface Application { id: string; name: string; tenant: string; namespace: string; status: string; version: number; ownerUserId?: string; maxConcurrent?: number; tokenBudget?: number; tokensUsed?: number; members?: { userId: string; roles: string[] }[] }
export interface ApplicationCredential { id: string; name: string; status: string; scopes: string[]; targets: SessionTarget[]; expiresAt?: string }
export const listApplications = (tenant: string, namespace: string) => api.get<{ items: Application[] }>(`/api/v1/applications?${new URLSearchParams({ tenant, namespace })}`);
export const createApplication = (body: { tenant: string; namespace: string; name: string }) => api.post<{ application: Application }>('/api/v1/applications', body);
export const listApplicationCredentials = (id: string) => api.get<{ items: ApplicationCredential[] }>(`/api/v1/applications/${encodeURIComponent(id)}/credentials`);
export const createApplicationCredential = (id: string, body: { name: string; scopes: string[]; targets: SessionTarget[] }) => api.post<{ credential: ApplicationCredential; apiKey: string }>(`/api/v1/applications/${encodeURIComponent(id)}/credentials`, body);
export const revokeApplicationCredential = (id: string, credential: string) => api.delete(`/api/v1/applications/${encodeURIComponent(id)}/credentials/${encodeURIComponent(credential)}`);
