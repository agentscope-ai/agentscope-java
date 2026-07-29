import { getToken } from './auth';

export interface ChannelInfo {
  channelId: string;
  type?: string | null;
  dmScope: string | null;
  defaultAgentId: string | null;
  disabled?: boolean;
  started: boolean;
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
  sessionScope?: 'MAIN' | 'PER_PEER' | 'PER_CHANNEL_PEER' | 'PER_ACCOUNT_CHANNEL_PEER';
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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
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

export async function listChannelTypes(): Promise<string[]> {
  const res = await fetch('/api/channels/types', { headers: authHeaders() });
  if (!res.ok) return failOn(res, 'Failed to load channel types');
  return res.json();
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
