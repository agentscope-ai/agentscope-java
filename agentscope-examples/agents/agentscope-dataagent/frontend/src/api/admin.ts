import { getToken } from './auth';

function authHeaders() {
  return { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' };
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: authHeaders(), ...init });
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

// ---------- Overview ----------

export interface ActivityEntry {
  sessionKey: string;
  agentId: string;
  userId: string;
  lastActivityMs: number;
  kind: string;
}

export interface OverviewView {
  activeSessionCount: number;
  totalUserCount: number;
  registeredAgentCount: number;
  registeredChannelCount: number;
  recentActivity: ActivityEntry[];
}

export const getOverview = () => apiFetch<OverviewView>('/api/admin/runtime/overview');

// ---------- Instances (registered agent beans) ----------

/**
 * One registered agent bean inside the claw runtime. There is exactly one of
 * these per agentId declared in agentscope.json — they are long-lived, NOT
 * per-session.
 */
export interface InstanceView {
  agentId: string;
  className: string;
}

export const listInstances = () => apiFetch<InstanceView[]>('/api/admin/runtime/instances');

// ---------- Sessions (live, per user/turn) ----------

export interface AdminSessionView {
  sessionKey: string;
  agentId: string;
  userId: string | null;
  kind: string;
  lastActivityMs: number;
  idleMs: number;
}

export const listAdminSessions = (limit = 100) =>
  apiFetch<AdminSessionView[]>(`/api/admin/runtime/sessions?limit=${limit}`);

// ---------- Users ----------

export interface UserView {
  userId: string;
  username: string;
  roles: string[];
}

export const listUsers = () => apiFetch<UserView[]>('/api/admin/users');

// ---------- Channels ----------

export interface BindingView {
  agentId: string;
  peerId: string | null;
  guildId: string | null;
  roomId: string | null;
  sessionScope: string | null;
  priority: number;
}

export interface ChannelView {
  channelId: string;
  dmScope: string;
  defaultAgentId: string | null;
  started: boolean;
  outboundQueueSize: number;
  bindingCount: number;
  bindings: BindingView[];
}

export const listChannels = () => apiFetch<ChannelView[]>('/api/admin/runtime/channels');
export const getChannel = (id: string) => apiFetch<ChannelView>(`/api/admin/runtime/channels/${encodeURIComponent(id)}`);

// ---------- Detail pages (agent ↔ channel ↔ session) ----------

export interface ChannelBindingRef {
  channelId: string;
  isDefault: boolean;
  matchedTiers: string[];
}

export interface AgentSessionRef {
  sessionKey: string;
  userId: string | null;
  kind: string;
  lastActivityMs: number;
  idleMs: number;
}

export interface AgentDefinitionFull {
  id: string;
  name: string;
  description: string | null;
  sysPrompt: string | null;
  model: string | null;
  maxIters: number | null;
  toolsAllow: string[] | null;
  toolsDeny:  string[] | null;
  identityName:  string | null;
  identityEmoji: string | null;
  groupChatMentionPatterns: string[] | null;
  groupChatRequireMention:  boolean | null;
  sandboxMode:  string | null;
  sandboxScope: string | null;
  skillsAllow: string[] | null;
  skillsDeny:  string[] | null;
  isMain: boolean;
  liveInGateway: boolean;
  workspacePath: string | null;
  workspaceExists: boolean;
}

export interface AgentDetailView {
  definition: AgentDefinitionFull;
  channels: ChannelBindingRef[];
  sessions: AgentSessionRef[];
  users: string[];
}

export const getAgentDetail = (id: string) =>
  apiFetch<AgentDetailView>(`/api/admin/agents/${encodeURIComponent(id)}/detail`);

export interface ChannelSessionRef {
  sessionKey: string;
  agentId: string;
  userId: string | null;
  kind: string;
  lastActivityMs: number;
  idleMs: number;
}

export interface ChannelDetailView {
  channel: ChannelView;
  agents: string[];
  sessions: ChannelSessionRef[];
  users: string[];
}

export const getChannelDetail = (id: string) =>
  apiFetch<ChannelDetailView>(`/api/admin/channels/${encodeURIComponent(id)}/detail`);

// ---------- Routing topology (overview card) ----------

export interface TopologyEdge {
  channelId: string;
  agentId: string;
  tier: string;
  sessionScope: string | null;
}

export interface RoutingTopologyView {
  channels: string[];
  agents: string[];
  edges: TopologyEdge[];
  totalSessions: number;
}

export const getRoutingTopology = () =>
  apiFetch<RoutingTopologyView>('/api/admin/channels/topology');

// ---------- Session route info ----------

export interface MatchedBinding {
  index: number;
  agentId: string;
  tier: string;
  sessionScope: string | null;
}

export interface SessionRouteInfo {
  sessionKey: string;
  agentId: string;
  channelId: string | null;
  gateKey: string | null;
  matchedBy: string | null;
  candidateBindings: MatchedBinding[];
}

export const getSessionRouteInfo = (key: string) =>
  apiFetch<SessionRouteInfo>(`/api/admin/sessions/${encodeURIComponent(key)}/route-info`);

// ---------- Bindings (config-file CRUD) ----------

/** Server-side editable binding entry (matches AdminBindingController.BindingView). */
export interface EditableBinding {
  index: number;
  agentId: string;
  peer: string | null;
  parentPeer: string | null;
  guild: string | null;
  roles: string[] | null;
  team: string | null;
  account: string | null;
  channel: string | null;
  sessionScope: string | null;
  tier: string;
}

export interface BindingMutationRequest {
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

export interface BindingMutationResult {
  index: number;
  restartRequired: boolean;
  message: string;
}

export const listBindings = (channelId: string) =>
  apiFetch<EditableBinding[]>(`/api/admin/channels/${encodeURIComponent(channelId)}/bindings`);

export const createBinding = (channelId: string, req: BindingMutationRequest) =>
  apiFetch<BindingMutationResult>(
    `/api/admin/channels/${encodeURIComponent(channelId)}/bindings`,
    { method: 'POST', body: JSON.stringify(req) },
  );

export const updateBinding = (channelId: string, index: number, req: BindingMutationRequest) =>
  apiFetch<BindingMutationResult>(
    `/api/admin/channels/${encodeURIComponent(channelId)}/bindings/${index}`,
    { method: 'PUT', body: JSON.stringify(req) },
  );

export const deleteBinding = async (channelId: string, index: number): Promise<void> => {
  const res = await fetch(
    `/api/admin/channels/${encodeURIComponent(channelId)}/bindings/${index}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
};

// ---------- Config ----------

export const getAgentscopeConfig = () => apiFetch<unknown>('/api/admin/config/agentscope');
export const putAgentscopeConfig = (body: unknown) =>
  apiFetch<{ success: boolean; message: string }>('/api/admin/config/agentscope', {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const getRuntimeConfig = () => apiFetch<Record<string, unknown>>('/api/admin/config/runtime');
export const putRuntimeConfig = (body: unknown) =>
  apiFetch<{ success: boolean; message: string }>('/api/admin/config/runtime', {
    method: 'PUT',
    body: JSON.stringify(body),
  });

// ---------- Agent config update ----------

export interface AgentUpdateRequest {
  name: string;
  description?: string;
  sysPrompt?: string;
  model?: string;
  maxIters?: number;
  toolsAllow?: string[];
  toolsDeny?: string[];
  identityName?: string;
  identityEmoji?: string;
  groupChatMentionPatterns?: string[];
  groupChatRequireMention?: boolean;
  sandboxMode?: string;
  sandboxScope?: string;
  skillsAllow?: string[];
  skillsDeny?: string[];
}

export const updateAgentConfig = (id: string, req: AgentUpdateRequest) =>
  apiFetch<{ id: string; restartRequired: boolean; message: string }>(
    `/api/admin/agents/${encodeURIComponent(id)}`,
    { method: 'PUT', body: JSON.stringify(req) },
  );

// ---------- Workspace ----------

export interface WorkspaceSummary {
  agentId: string;
  workspacePath: string;
  exists: boolean;
  agentsMdExists: boolean;
  memoryMdExists: boolean;
  skillCount: number;
  subagentCount: number;
  dailyMemoryCount: number;
}

export interface WorkspaceFileContent {
  name: string;
  content: string;
  exists: boolean;
}

export interface WorkspaceSkillEntry {
  name: string;
  summary: string;
  hasSKILLmd: boolean;
}

export interface WorkspaceSubagentEntry {
  name: string;
  summary: string;
}

export interface WorkspaceMemoryView {
  memoryMd: string | null;
  dailyFiles: { name: string; sizeBytes: number }[];
}

export interface WorkspaceFileTreeEntry {
  path: string;
  isDirectory: boolean;
  sizeBytes: number;
}

export interface WorkspaceSessionEntry {
  sessionKey: string;
  sessionId: string;
  userId: string | null;
  label: string | null;
  kind: string | null;
  lastActivityMs: number;
  createdAtMs: number;
  idleMs: number;
}

const wsBase = (id: string) => `/api/admin/agents/${encodeURIComponent(id)}/workspace`;

export const getWorkspaceSummary = (id: string) =>
  apiFetch<WorkspaceSummary>(wsBase(id));
export const scaffoldWorkspace = (id: string, name?: string) =>
  apiFetch<WorkspaceSummary>(`${wsBase(id)}/scaffold?name=${encodeURIComponent(name ?? '')}`, { method: 'POST' });

export const getAgentsMd = (id: string) =>
  apiFetch<WorkspaceFileContent>(`${wsBase(id)}/agents-md`);
export const putAgentsMd = (id: string, content: string) =>
  apiFetch<{ name: string; success: boolean }>(`${wsBase(id)}/agents-md`, {
    method: 'PUT', body: JSON.stringify({ name: 'AGENTS.md', content, exists: true }),
  });

export const getWorkspaceMemory = (id: string) =>
  apiFetch<WorkspaceMemoryView>(`${wsBase(id)}/memory`);
export const getWorkspaceDailyMemory = (id: string, filename: string) =>
  apiFetch<WorkspaceFileContent>(`${wsBase(id)}/memory/${encodeURIComponent(filename)}`);

export const listWorkspaceSkills = (id: string) =>
  apiFetch<WorkspaceSkillEntry[]>(`${wsBase(id)}/skills`);
export const getWorkspaceSkill = (id: string, name: string) =>
  apiFetch<WorkspaceFileContent>(`${wsBase(id)}/skills/${encodeURIComponent(name)}`);
export const createWorkspaceSkill = (id: string, name: string, content?: string) =>
  apiFetch<{ name: string; success: boolean }>(`${wsBase(id)}/skills`, {
    method: 'POST', body: JSON.stringify({ name, content }),
  });
export const updateWorkspaceSkill = (id: string, name: string, content: string) =>
  apiFetch<{ name: string; success: boolean }>(`${wsBase(id)}/skills/${encodeURIComponent(name)}`, {
    method: 'PUT', body: JSON.stringify({ name, content, exists: true }),
  });
export const deleteWorkspaceSkill = async (id: string, name: string): Promise<void> => {
  const res = await fetch(`${wsBase(id)}/skills/${encodeURIComponent(name)}`, {
    method: 'DELETE', headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await res.text().catch(() => res.statusText));
};

export const listWorkspaceSubagents = (id: string) =>
  apiFetch<WorkspaceSubagentEntry[]>(`${wsBase(id)}/subagents`);
export const getWorkspaceSubagent = (id: string, name: string) =>
  apiFetch<WorkspaceFileContent>(`${wsBase(id)}/subagents/${encodeURIComponent(name)}`);
export const createWorkspaceSubagent = (id: string, name: string, content?: string) =>
  apiFetch<{ name: string; success: boolean }>(`${wsBase(id)}/subagents`, {
    method: 'POST', body: JSON.stringify({ name, content }),
  });
export const updateWorkspaceSubagent = (id: string, name: string, content: string) =>
  apiFetch<{ name: string; success: boolean }>(`${wsBase(id)}/subagents/${encodeURIComponent(name)}`, {
    method: 'PUT', body: JSON.stringify({ name, content, exists: true }),
  });
export const deleteWorkspaceSubagent = async (id: string, name: string): Promise<void> => {
  const res = await fetch(`${wsBase(id)}/subagents/${encodeURIComponent(name)}`, {
    method: 'DELETE', headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await res.text().catch(() => res.statusText));
};

export const listWorkspaceFiles = (id: string, recursive = false) =>
  apiFetch<WorkspaceFileTreeEntry[]>(`${wsBase(id)}/files?recursive=${recursive}`);

export const listWorkspaceSessions = (id: string) =>
  apiFetch<WorkspaceSessionEntry[]>(`${wsBase(id)}/sessions`);

// ---------- Debug ----------

export interface DebugInfo {
  application: string;
  startedAt: string;
  javaVersion: string;
  osName: string;
  modelName: string;
  apiKeyConfigured: boolean;
  logAppenderAttached: boolean;
}

export const getDebugInfo = () => apiFetch<DebugInfo>('/api/admin/debug/info');
export const getRecentLogs = () => apiFetch<string[]>('/api/admin/debug/logs');

/**
 * Opens a fetch-based SSE log stream.
 * Returns a cancel function that aborts the stream.
 */
export function openLogStream(
  onLine: (line: string) => void,
  onError?: (err: string) => void,
): () => void {
  const controller = new AbortController();
  (async () => {
    try {
      const res = await fetch('/api/admin/runtime/logs', {
        headers: authHeaders(),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        onError?.(`HTTP ${res.status}`);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const parts = buf.split('\n');
        buf = parts.pop() ?? '';
        for (const part of parts) {
          const line = part.startsWith('data:') ? part.slice(5).trim() : part.trim();
          if (line) onLine(line);
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') onError?.((e as Error).message);
    }
  })();
  return () => controller.abort();
}
