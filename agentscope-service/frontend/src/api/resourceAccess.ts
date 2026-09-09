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
export const resourceActions = ['discover', 'use', 'inspect', 'edit', 'publish', 'manage'] as const;
export type ResourceAction = typeof resourceActions[number];
export type AccessGroup = { name: string; members: string[]; roles: string[] };
export type Resource = { kind: string; id: string; name: string; dependencies: string[] | null };
export type ResourcePolicy = { mode: 'inherit' | 'restricted'; users?: Record<string, string[]>; groups?: Record<string, string[]>; consumers?: string[]; exportTo?: string[] };
export type Decision = { allowed: boolean; action: string; reason: string; sources: string[] };
export type ResourceAccess = { resource: Resource; decisions: Decision[]; version: number; canManage: boolean; policy?: ResourcePolicy; groups?: Record<string, AccessGroup>; dependents?: Resource[]; dependencyError?: string };
export type AccessRequest = { id: string; user: string; resource: string; action: string; reason: string; status: string; reviewedBy?: string; createdAt: string };
const base = (namespace: string) => `/api/v1/namespaces/${encodeURIComponent(namespace)}`;
const resourcePath = (namespace: string, kind: string, id: string) => `${base(namespace)}/resources/${encodeURIComponent(kind)}/${encodeURIComponent(id)}/access`;
export const resourceURL = (namespace: string, kind: string, id: string) => `/settings/namespaces/${encodeURIComponent(namespace)}/resources/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`;
export const listResources = (n: string) => api.get<{ items: { resource: Resource; actions: string[] }[]; version: number }>(`${base(n)}/resources`);
export const getResourceAccess = (n: string, kind: string, id: string) => api.get<ResourceAccess>(resourcePath(n, kind, id));
export const saveResourceAccess = (n: string, kind: string, id: string, version: number, policy: ResourcePolicy) => api.put(resourcePath(n, kind, id), { version, policy });
export const getGroups = (n: string) => api.get<{ groups: Record<string, AccessGroup>; version: number; canManage: boolean }>(`${base(n)}/groups`);
export const saveGroups = (n: string, version: number, groups: Record<string, AccessGroup>) => api.put(`${base(n)}/groups`, { version, groups });
export const getAccessRequests = (n: string) => api.get<{ items: AccessRequest[]; version: number; canManage: boolean }>(`${base(n)}/requests`);
export const requestResourceAccess = (n: string, version: number, resource: string, action: string, reason: string) => api.post(`${base(n)}/requests`, { version, resource, action, reason });
export const reviewAccessRequest = (n: string, id: string, version: number, approve: boolean) => api.post(`${base(n)}/requests/${encodeURIComponent(id)}/review`, { version, approve });
export type SharedTemplate = { sourceNamespace: string; id: string; name: string; description: string; revisionId: string; revision: number; sourceVersion: number; dependencies: string[] };
export const sharedTemplates = (n: string) => api.get<{ items: SharedTemplate[]; canImport: boolean }>(`${base(n)}/shared-templates`);
export const importTemplate = (n: string, template: SharedTemplate, name: string, bindings: Record<string, string>) => api.post<{ definition: { id: string } }>(`${base(n)}/import-template`, { ...template, name, bindings });
