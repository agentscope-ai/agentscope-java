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
