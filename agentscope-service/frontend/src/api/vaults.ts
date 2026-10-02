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

export interface Vault {
  id: string;
  ownerId?: string;
  displayName: string;
  metadata?: Record<string, unknown>;
  createdAt: number;
  updatedAt: number;
}

export interface VaultCredential {
  id: string;
  type: string;
  label: string;
  target: string;
  createdAt: number;
}

export interface CreateVaultRequest {
  displayName: string;
  metadata?: Record<string, unknown>;
}

export interface AddCredentialRequest {
  type: string;
  label: string;
  target: string;
  secret: string;
}


export async function listVaults(): Promise<Vault[]> {
  const res = await fetch('/api/vaults', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list vaults');
  return res.json();
}

export async function getVault(id: string): Promise<Vault> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load vault');
  return res.json();
}

export async function createVault(req: CreateVaultRequest): Promise<Vault> {
  const res = await fetch('/api/vaults', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create vault');
  return res.json();
}

export async function updateVault(
  id: string,
  req: { displayName?: string; metadata?: Record<string, unknown> },
): Promise<Vault> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update vault');
  return res.json();
}

export async function archiveVault(id: string): Promise<{ id: string; archivedAt: number }> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive vault');
  return res.json();
}

export async function deleteVault(id: string): Promise<void> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete vault');
}

export async function listCredentials(vaultId: string): Promise<VaultCredential[]> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to list credentials');
  return res.json();
}

export async function addCredential(vaultId: string, req: AddCredentialRequest): Promise<VaultCredential> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials`,
    { method: 'POST', headers: authHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to add credential');
  return res.json();
}

export async function updateCredential(
  vaultId: string,
  credentialId: string,
  req: { label?: string; target?: string; secret?: string },
): Promise<VaultCredential> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
    { method: 'PATCH', headers: authHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to update credential');
  return res.json();
}

export interface CredentialValidateResult {
  ok: boolean;
  checks: Record<string, string>;
  checkedAt: number;
}

export async function validateCredential(
  vaultId: string,
  credentialId: string,
): Promise<CredentialValidateResult> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}/validate`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to validate credential');
  return res.json();
}

export async function deleteCredential(vaultId: string, credentialId: string): Promise<void> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete credential');
}
