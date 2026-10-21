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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

export type NamespaceSummary = { tenant: string; name: string; displayName: string; kind: 'personal' | 'shared' | 'global'; roles: string[]; accessVersion?: number; groups?: string[] };

let selected: { tenant: string; namespace: string; token: string | null } | undefined;
export function setRequestNamespace(tenant: string, namespace: string, token: string | null) { selected = { tenant, namespace, token }; }
export function namespaceHeaders(): Record<string, string> {
  if (!selected || typeof localStorage === 'undefined' || selected.token !== localStorage.getItem('claw_token')) return {};
  return { 'X-AgentScope-Tenant': selected.tenant, 'X-AgentScope-Namespace': selected.namespace };
}
export function resolveAuthorizedNamespace(items: NamespaceSummary[], tenant: string, name: string, fallback: string): NamespaceSummary | undefined {
  return items.find(n => n.tenant === tenant && n.name === name) ?? items.find(n => n.name === fallback) ?? items[0];
}
export function namespaceCan(roles: string[], action: 'write' | 'configure' | 'manage' | 'audit' | 'operate') {
  if (action === 'audit') return roles.includes('auditor');
  if (roles.includes('admin')) return true;
  if (action === 'manage') return false;
  if (action === 'operate') return roles.includes('operator');
  return roles.includes('developer') || (action === 'write' && roles.includes('member'));
}
