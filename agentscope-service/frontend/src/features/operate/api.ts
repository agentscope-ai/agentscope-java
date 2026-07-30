import { api, ApiError } from '@/lib/apiClient';

export interface RuntimeSession {
  id: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  framework?: string;
  phase: string;
  busy?: boolean | null;
  instanceRef?: string;
  startedAt?: string;
  lastActiveAt?: string;
  instanceHealthy?: boolean;
  capabilities?: string[];
  contractLevel?: number;
  model?: string;
  snapshot?: {
    messageCount?: number;
    promptTokens?: number;
    completionTokens?: number;
    totalTokens?: number;
    contextPressure?: number;
    isCompacted?: boolean;
    effectiveMessageCount?: number;
    contextHash?: string;
  };
}

export interface AgentUsage {
  agentName: string;
  namespace: string;
  totalTokens: number;
  activeSessions: number;
  avgPressure?: number;
  errorCount?: number;
}

export interface HighPressureSession {
  sessionId: string;
  agentName: string;
  namespace: string;
  phase?: string;
  contextPressure?: number;
  totalTokens?: number;
}

export interface StaleDataplane {
  instanceId: string;
  agentName: string;
  namespace: string;
  lastSeenAt?: string;
}

export interface OrphanSession {
  sessionId: string;
  agentName: string;
  namespace: string;
  instanceRef?: string;
}

export interface FleetOverview {
  agentCount: number;
  instanceCount: number;
  healthyInstanceCount?: number;
  staleInstanceCount?: number;
  dataplaneCount: number;
  sessionCount: number;
  activeSessionCount: number;
  sessionsByPhase?: Record<string, number>;
  avgContextPressure: number;
  p95ContextPressure?: number;
  tokenUsage24h: number;
  errorCount24h?: number;
  topAgents?: AgentUsage[];
  highPressureSessions?: HighPressureSession[];
  staleDataplanes?: StaleDataplane[];
  orphanSessions?: OrphanSession[];
}

export interface ManagedAgentSummary {
  name: string;
  namespace: string;
  type?: string;
  runtime?: string;
  displayName?: string;
  replicas?: string;
  activeSessions?: number;
  revision?: number;
}

export interface DataPlaneEntry {
  agentName: string;
  namespace: string;
  instanceId: string;
  baseUrl: string;
  runtime?: string;
  framework?: string;
  contractLevel: number;
  capabilities?: string[];
  healthy: boolean;
  lastSeenAt: string;
  source: string;
}

export interface TokenBucket {
  bucketStart: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  sampleCount: number;
}

export interface OverviewTimeseries {
  metric: string;
  bucket: string;
  points: TokenBucket[];
}

export interface AgentMetric {
  id: number;
  agentName: string;
  namespace: string;
  recordedAt: string;
  activeSessions: number;
  totalMessages?: number;
  totalTokens?: number;
  avgContextPressure?: number;
  errorCount?: number;
  uptimeSeconds?: number;
}

export interface SessionCommand {
  id: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  command: string;
  operator?: string;
  source?: string;
  status: string;
  code?: string;
  error?: string;
  requestedAt: string;
  completedAt?: string;
  durationMs?: number;
}

export interface SessionTask {
  id?: string;
  taskId?: string;
  subject?: string;
  name?: string;
  state?: string;
  status?: string;
  description?: string;
  [key: string]: unknown;
}

export interface InventorySubagent {
  name: string;
  description?: string;
  tools?: string[];
  workspaceMode?: string;
  url?: string;
  invokeCount?: number;
  lastInvokedAt?: string;
}

export interface InventoryWorkspace {
  path: string;
  mode?: string;
  sizeBytes?: number;
  ownerRef?: string;
}

export function fetchOverview() {
  return api.get<FleetOverview>('/api/v1/overview');
}

export function fetchOverviewTimeseries(params?: { metric?: string; bucket?: string }) {
  const q = new URLSearchParams();
  q.set('metric', params?.metric || 'tokens');
  q.set('bucket', params?.bucket || '1h');
  return api.get<OverviewTimeseries>(`/api/v1/overview/timeseries?${q}`);
}

export function fetchAgentMetrics(params?: { agent?: string; namespace?: string; since?: string }) {
  const q = new URLSearchParams();
  if (params?.agent) q.set('agent', params.agent);
  if (params?.namespace) q.set('namespace', params.namespace);
  if (params?.since) q.set('since', params.since);
  const qs = q.toString();
  return api.get<{ metrics: AgentMetric[] }>(`/api/v1/metrics/agents${qs ? `?${qs}` : ''}`);
}

export function fetchRuntimeSessions(params?: { agent?: string; phase?: string }) {
  const q = new URLSearchParams();
  if (params?.agent) q.set('agent', params.agent);
  if (params?.phase) q.set('phase', params.phase);
  const qs = q.toString();
  return api.get<{ sessions: RuntimeSession[] }>(`/api/v1/sessions${qs ? `?${qs}` : ''}`);
}

export function fetchRuntimeSession(id: string, agent?: string) {
  const q = agent ? `?agent=${encodeURIComponent(agent)}` : '';
  return api.get<RuntimeSession>(`/api/v1/sessions/${encodeURIComponent(id)}${q}`);
}

export function fetchSessionEvents(id: string) {
  return api.get<{ events: Array<Record<string, unknown>> }>(`/api/v1/sessions/${encodeURIComponent(id)}/events`);
}

export function fetchSessionContext(id: string) {
  return api.get<Record<string, unknown>>(`/api/v1/sessions/${encodeURIComponent(id)}/context`);
}

export function fetchSessionMessages(id: string) {
  return api.get<Record<string, unknown>>(`/api/v1/sessions/${encodeURIComponent(id)}/messages?limit=200`);
}

export function fetchSessionTasks(id: string) {
  return api.get<{ tasks?: SessionTask[] } | SessionTask[]>(`/api/v1/sessions/${encodeURIComponent(id)}/tasks`);
}

export function fetchSessionSubagentTasks(id: string) {
  return api.get<{ tasks?: SessionTask[] }>(`/api/v1/sessions/${encodeURIComponent(id)}/subagent-tasks`);
}

export function setSessionPlanMode(id: string, active: boolean) {
  return api.post<{ accepted?: boolean; active?: boolean }>(
    `/api/v1/sessions/${encodeURIComponent(id)}/plan-mode`,
    { active },
  );
}

export function fetchSessionCommands(id: string) {
  return api.get<{ commands: SessionCommand[] }>(`/api/v1/sessions/${encodeURIComponent(id)}/commands`);
}

export function compressSession(id: string, opts?: { force?: boolean; queue?: boolean }) {
  return api.post<{
    accepted?: boolean;
    commandId?: string;
    phase?: string;
    forced?: boolean;
    queued?: boolean;
    cached?: boolean;
  }>(`/api/v1/sessions/${encodeURIComponent(id)}/compress`, {
    force: opts?.force === true,
    queue: opts?.queue,
  });
}

export function terminateSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/terminate`);
}

export function abortSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/abort`);
}

export function fetchManagedAgents() {
  return api.get<{ items: ManagedAgentSummary[] }>('/api/v1/agents');
}

export function fetchManagedAgent(name: string, namespace = 'default') {
  return api.get<Record<string, unknown>>(
    `/api/v1/agents/${encodeURIComponent(name)}?namespace=${encodeURIComponent(namespace)}`,
  );
}

export function fetchDataPlanes(agent?: string, namespace = 'default') {
  const q = agent ? `?agent=${encodeURIComponent(agent)}&namespace=${encodeURIComponent(namespace)}` : '';
  return api.get<{ dataplanes: DataPlaneEntry[] }>(`/api/v1/dataplanes${q}`);
}

/** Graceful GET that returns null on 404/501 (BYO inventory may be unavailable). */
export async function fetchOptional<T>(path: string): Promise<T | null> {
  try {
    return await api.get<T>(path);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 501 || e.status === 503)) {
      return null;
    }
    throw e;
  }
}

export function fetchAgentSubagents(name: string, namespace = 'default') {
  return fetchOptional<{
    agent: string;
    namespace: string;
    source?: string;
    instances: Array<{
      instanceId: string;
      source?: string;
      healthy?: boolean;
      subagents: InventorySubagent[];
    }>;
  }>(`/api/v1/agents/${encodeURIComponent(name)}/subagents?namespace=${encodeURIComponent(namespace)}`);
}

export function fetchAgentWorkspaces(name: string, namespace = 'default') {
  return fetchOptional<{
    agent: string;
    namespace: string;
    source?: string;
    instances: Array<{
      instanceId: string;
      source?: string;
      healthy?: boolean;
      workspaces: InventoryWorkspace[];
    }>;
  }>(`/api/v1/agents/${encodeURIComponent(name)}/workspaces?namespace=${encodeURIComponent(namespace)}`);
}
