import { api } from '@/lib/apiClient';

export interface RuntimeSession {
  id: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  framework?: string;
  phase: string;
  instanceRef?: string;
  startedAt?: string;
  lastActiveAt?: string;
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

export interface FleetOverview {
  agentCount: number;
  instanceCount: number;
  dataplaneCount: number;
  sessionCount: number;
  activeSessionCount: number;
  avgContextPressure: number;
  tokenUsage24h: number;
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

export function fetchOverview() {
  return api.get<FleetOverview>('/api/v1/overview');
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

export function compressSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/compress`);
}

export function terminateSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/terminate`);
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
