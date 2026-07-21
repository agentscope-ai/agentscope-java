import { getToken } from './auth';

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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export async function listVaults(): Promise<Vault[]> {
  const res = await fetch('/api/vaults', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to list vaults: ${res.status}`);
  return res.json();
}

export async function getVault(id: string): Promise<Vault> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to load vault: ${res.status}`);
  return res.json();
}

export async function createVault(req: CreateVaultRequest): Promise<Vault> {
  const res = await fetch('/api/vaults', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to create vault: ${res.status}`);
  }
  return res.json();
}

export async function deleteVault(id: string): Promise<void> {
  const res = await fetch(`/api/vaults/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete vault: ${res.status}`);
}

export async function listCredentials(vaultId: string): Promise<VaultCredential[]> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error(`Failed to list credentials: ${res.status}`);
  return res.json();
}

export async function addCredential(vaultId: string, req: AddCredentialRequest): Promise<VaultCredential> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials`,
    { method: 'POST', headers: authHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to add credential: ${res.status}`);
  }
  return res.json();
}

export async function deleteCredential(vaultId: string, credentialId: string): Promise<void> {
  const res = await fetch(
    `/api/vaults/${encodeURIComponent(vaultId)}/credentials/${encodeURIComponent(credentialId)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw new Error(`Failed to delete credential: ${res.status}`);
}
