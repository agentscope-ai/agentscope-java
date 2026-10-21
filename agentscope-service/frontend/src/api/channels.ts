import { namespaceHeaders } from "@/lib/namespaceScope";
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

import { getToken } from './auth';

export interface ChannelInfo {
  workEnabled?: boolean;
  workTargets?: ChannelWorkTarget[];
  channelId: string;
  type?: string | null;
  dmScope: string | null;
  defaultAgentId: string | null;
  disabled?: boolean;
  started: boolean;
  lastError?: string | null;
}

export interface ChannelFieldSpec {
  key: string;
  label: string;
  required: boolean;
  secret: boolean;
  inputType: 'text' | 'password' | 'number' | string;
  advanced?: boolean;
  hint?: string;
}

export interface ChannelTypeSpec {
  type: string;
  label: string;
  transport: 'stream' | 'callback' | 'webhook' | string;
  callbackUrlTemplate?: string;
  hint?: string;
  fields: ChannelFieldSpec[];
}

export type BindingTier =
  | 'peer'
  | 'parentPeer'
  | 'guildRoles'
  | 'guild'
  | 'team'
  | 'account'
  | 'channel';

export interface AgentBinding {
  channelId: string;
  index: number;
  tier: BindingTier;
  peer?: string;
  parentPeer?: string;
  guild?: string;
  roles?: string[];
  team?: string;
  account?: string;
  channel?: string;
  sessionScope?: 'MAIN' | 'PER_PEER';
}

export interface BindingCreateRequest {
  channelId: string;
  tier: BindingTier;
  peer?: string;
  parentPeer?: string;
  guild?: string;
  roles?: string[];
  team?: string;
  account?: string;
  channel?: string;
  sessionScope?: AgentBinding['sessionScope'];
}

export interface BindingConfigEntry {
  agentId: string;
  peer?: string;
  parentPeer?: string;
  guild?: string;
  roles?: string[];
  team?: string;
  account?: string;
  channel?: string;
  sessionScope?: string;
}

export interface ChannelDetail {
  channelId: string;
  type: string;
  dmScope: string | null;
  defaultAgentId: string | null;
  disabled: boolean;
  started: boolean;
  lastError?: string | null;
  properties?: Record<string, unknown>;
  bindings: BindingConfigEntry[];
}

export interface ChannelUpsertRequest {
  channelId?: string;
  type: string;
  dmScope?: string | null;
  defaultAgentId?: string | null;
  disabled?: boolean | null;
  properties?: Record<string, unknown> | null;
  bindings?: BindingConfigEntry[] | null;
}

/** Agent-scoped convenience projection for channels whose default target is this Agent. */
export interface AgentPresence {
  channelId: string;
  platform: string;
  isolation: 'per_person' | 'shared';
  enabled: boolean;
  started: boolean;
  lastError?: string | null;
  credentials: Record<string, unknown>;
  callbackUrl?: string | null;
}

export interface PresenceUpsertRequest {
  channelId?: string;
  platform: string;
  isolation?: 'per_person' | 'shared';
  enabled?: boolean;
  credentials: Record<string, unknown>;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}`, ...namespaceHeaders() } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function failOn(res: Response, fallback: string): Promise<never> {
  const msg = await res.text().catch(() => '');
  throw new Error(msg || `${fallback} (${res.status})`);
}

export async function listChannels(): Promise<ChannelInfo[]> {
  const res = await fetch('/api/channels', { headers: authHeaders() });
  if (!res.ok) return failOn(res, 'Failed to load channels');
  return res.json();
}

export async function listChannelTypes(): Promise<ChannelTypeSpec[]> {
  const res = await fetch('/api/channels/types', { headers: authHeaders() });
  if (!res.ok) return failOn(res, 'Failed to load channel types');
  const data = await res.json();
  // Back-compat: older servers returned string[]
  if (Array.isArray(data) && data.length > 0 && typeof data[0] === 'string') {
    return (data as string[]).map((t) => ({
      type: t,
      label: t,
      transport: 'stream',
      fields: [],
    }));
  }
  return data;
}

export async function getChannelDetail(channelId: string): Promise<ChannelDetail> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) return failOn(res, 'Failed to load channel');
  return res.json();
}

export async function createChannel(req: ChannelUpsertRequest): Promise<ChannelDetail> {
  const res = await fetch('/api/channels', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) return failOn(res, 'Failed to create channel');
  return res.json();
}

export async function updateChannel(
  channelId: string,
  req: ChannelUpsertRequest,
): Promise<ChannelDetail> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) return failOn(res, 'Failed to update channel');
  return res.json();
}

export async function deleteChannel(channelId: string): Promise<void> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) return failOn(res, 'Failed to delete channel');
}

export async function enableChannel(channelId: string): Promise<void> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}/enable`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) return failOn(res, 'Failed to enable channel');
}

export async function disableChannel(channelId: string): Promise<void> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}/disable`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) return failOn(res, 'Failed to disable channel');
}

export async function listAgentBindings(agentId: string): Promise<AgentBinding[]> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/bindings`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to load agent bindings');
  return res.json();
}

export async function addBinding(
  agentId: string,
  req: BindingCreateRequest,
): Promise<AgentBinding> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/bindings`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || 'Failed to add binding');
  }
  return res.json();
}

export async function updateBinding(
  agentId: string,
  channelId: string,
  index: number,
  req: BindingCreateRequest,
): Promise<AgentBinding> {
  const url = `/api/agents/${encodeURIComponent(agentId)}/bindings/${index}?channelId=${encodeURIComponent(channelId)}`;
  const res = await fetch(url, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error('Failed to update binding');
  return res.json();
}

export async function deleteBinding(
  agentId: string,
  channelId: string,
  index: number,
): Promise<void> {
  const url = `/api/agents/${encodeURIComponent(agentId)}/bindings/${index}?channelId=${encodeURIComponent(channelId)}`;
  const res = await fetch(url, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok && res.status !== 204) throw new Error('Failed to delete binding');
}

export async function setChannelDefault(agentId: string, channelId: string): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/channels/${encodeURIComponent(channelId)}/default`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('Failed to set channel default');
}

export async function listAgentPresences(agentId: string): Promise<AgentPresence[]> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/presences`, {
    headers: authHeaders(),
  });
  if (!res.ok) return failOn(res, 'Failed to load presences');
  return res.json();
}

export async function createAgentPresence(
  agentId: string,
  req: PresenceUpsertRequest,
): Promise<AgentPresence> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/presences`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) return failOn(res, 'Failed to create presence');
  return res.json();
}

export async function updateAgentPresence(
  agentId: string,
  channelId: string,
  req: PresenceUpsertRequest,
): Promise<AgentPresence> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/presences/${encodeURIComponent(channelId)}`,
    { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(req) },
  );
  if (!res.ok) return failOn(res, 'Failed to update presence');
  return res.json();
}

export async function deleteAgentPresence(agentId: string, channelId: string): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/presences/${encodeURIComponent(channelId)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) return failOn(res, 'Failed to delete presence');
}

export function resolveCallbackUrl(
  spec: ChannelTypeSpec | undefined,
  channelId: string,
): string | null {
  if (!spec?.callbackUrlTemplate) return null;
  const path = spec.callbackUrlTemplate.replace('{channelId}', channelId);
  if (typeof window !== 'undefined' && window.location?.origin) {
    return `${window.location.origin}${path}`;
  }
  return path;
}

export interface ChannelWorkTarget { targetType: 'agent' | 'team'; targetRef: string }
export interface ChannelWorkRoute extends ChannelWorkTarget { restrictGroups?: boolean; allowedGroups?: string[]; accountId: string; peerKind: 'DIRECT' | 'GROUP'; peerId: string; threadId?: string }
export interface ChannelWorkSettings {
  enabled: boolean; defaultTarget: ChannelWorkTarget; routes: ChannelWorkRoute[];
  allowGroupWork: boolean; notifyEvents: string[]; version: number;
}
export interface ChannelWorkActivity {
  inbounds?: { id: string; state: string; attempts: number }[];
  identities: { accountId: string; senderId: string }[];
  deliveries: { id: string; issueId?: string; state: string; attempts: number; providerMessageId: string; lastError: string }[];
  links: { id: string; issueId: string; active: boolean; address: { peerId: string; threadId?: string } }[];
}
async function channelWorkRequest<T>(channelId: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  const res = await fetch(`/api/channels/${encodeURIComponent(channelId)}/${path}`, {
    method, headers: jsonHeaders(), ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!res.ok) return failOn(res, 'Channel operation failed');
  return res.status === 204 ? undefined as T : res.json();
}
export const getChannelWorkSettings = (id: string) => channelWorkRequest<ChannelWorkSettings>(id, 'collaboration');
export const saveChannelWorkSettings = (id: string, value: ChannelWorkSettings) => channelWorkRequest<ChannelWorkSettings>(id, 'collaboration', 'PUT', value);
export const createChannelPairing = (id: string) => channelWorkRequest<{ command: string; expiresInSeconds: number }>(id, 'pairing', 'POST');
export const getChannelWorkActivity = (id: string) => channelWorkRequest<ChannelWorkActivity>(id, 'activity');
export const unlinkChannelIdentity = (id: string) => channelWorkRequest<void>(id, 'identity', 'DELETE');
export const retryChannelDelivery = (id: string, delivery: string) => channelWorkRequest<void>(id, `deliveries/${encodeURIComponent(delivery)}/retry`, 'POST');
export const unsubscribeChannelWork = (id: string, link: string) => channelWorkRequest<void>(id, `links/${encodeURIComponent(link)}`, 'DELETE');

export const retryChannelIntake = (id: string, message: string) => channelWorkRequest<void>(id, `messages/${encodeURIComponent(message)}/retry`, 'POST');
