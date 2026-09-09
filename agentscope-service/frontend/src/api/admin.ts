import { api } from '@/lib/apiClient';

export interface AdminUserView {
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
  disabled: boolean;
  version: number;
  createdAt: number;
}
export interface CreateUserRequest { username: string; initialPassword?: string; roles?: string[] }
export interface CreateUserResponse { user: AdminUserView; generatedPassword?: string }
export const listUsers = () => api.get<AdminUserView[]>('/api/admin/users');
export const createUser = (request: CreateUserRequest) => api.post<CreateUserResponse>('/api/admin/users', request);
export const resetPassword = (id: string, newPassword: string) => api.patch<AdminUserView>(`/api/admin/users/${encodeURIComponent(id)}/password`, { newPassword });
export const updateRoles = (id: string, roles: string[], version: number) => api.patch<AdminUserView>(`/api/admin/users/${encodeURIComponent(id)}/roles`, { roles, version });
export const setAccountDisabled = (id: string, disabled: boolean, version: number) => api.patch<AdminUserView>(`/api/admin/users/${encodeURIComponent(id)}/status`, { disabled, version });
export type AccountAudit = { id: number; userId: string; actor: string; action: string; details: Record<string, unknown>; createdAt: string };
export const listAccountAudit = (offset = 0) => api.get<{ items: AccountAudit[] }>(`/api/admin/access-audit?offset=${offset}`);
