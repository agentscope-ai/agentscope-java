import { getToken } from './auth';

export interface ChannelInfo {
  channelId: string;
  dmScope: string;
  defaultAgentId: string | null;
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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

export async function listChannels(): Promise<ChannelInfo[]> {
  const res = await fetch('/api/channels', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to load channels');
  return res.json();
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
  const res = await fetch(url, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(req) });
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
