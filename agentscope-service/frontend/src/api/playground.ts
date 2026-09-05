import { api } from '@/lib/apiClient';

export type PlaygroundTargetType = 'agent' | 'team' | 'workflow';
export type PlaygroundMode = 'conversation' | 'job';

export interface InvocationModeCapability {
  state: 'available' | 'unavailable' | 'not_supported' | 'partial' | string;
  reason: string;
}

export interface AgentInvocationCapabilities {
  agentId: string;
  job: InvocationModeCapability;
  conversation: InvocationModeCapability;
  features: Record<string, InvocationModeCapability>;
}

export interface PlaygroundInvocationResult {
  invocationId: string;
  mode: PlaygroundMode;
  status: string;
  sessionId?: string;
  /** Stable control-plane Session UUID used by detail and transcript APIs. */
  sessionRef?: string;
  bindingId?: string;
  issueId?: string;
  runId?: string;
  eventsUrl?: string;
  eventStreamUrl?: string;
  statusUrl?: string;
  [key: string]: unknown;
}

export const getAgentInvocationCapabilities = (agentId: string) =>
  api.get<{ capabilities: AgentInvocationCapabilities }>(
    `/api/v1/agents/${encodeURIComponent(agentId)}/invocation-capabilities`,
  );

export const invokePlayground = (body: {
  tenant: string;
  namespace: string;
  targetType: PlaygroundTargetType;
  targetRef: string;
  mode: PlaygroundMode;
  message: string;
  title?: string;
  input?: unknown;
  sessionId?: string;
}) => api.post<PlaygroundInvocationResult>('/api/v1/playground/invocations', body);

export const continuePlaygroundConversation = (
  sessionId: string,
  body: { tenant: string; namespace: string; agentId: string; message: string },
) => api.post<PlaygroundInvocationResult>(
  `/api/v1/playground/sessions/${encodeURIComponent(sessionId)}/turns`,
  body,
);
