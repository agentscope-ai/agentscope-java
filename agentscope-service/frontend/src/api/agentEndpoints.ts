import { api } from '@/lib/apiClient';
import { readApiError } from '@/api/http';

export type EndpointTargetType = 'agent' | 'team' | 'orchestration_revision';
export type EndpointInvocationMode = 'conversation' | 'job';

export interface AgentEndpoint {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  slug: string;
  targetType: EndpointTargetType;
  targetRef: string;
  invocationMode: EndpointInvocationMode;
  authPolicy: unknown;
  rateLimit?: { requests?: number; windowSeconds?: number };
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAgentEndpointRequest {
  tenant?: string;
  namespace?: string;
  name: string;
  slug: string;
  targetType: EndpointTargetType;
  targetRef: string;
  invocationMode: EndpointInvocationMode;
  rateLimit?: { requests: number; windowSeconds: number };
}

export const listAgentEndpoints = (tenant = 'default', namespace = 'default') => {
  const params = new URLSearchParams({ tenant, namespace });
  return api.get<{ items: AgentEndpoint[] }>(`/api/v1/agent-endpoints?${params}`);
};
export const createAgentEndpoint = (body: CreateAgentEndpointRequest) =>
  api.post<{ endpoint: AgentEndpoint; credential: string }>('/api/v1/agent-endpoints', body);
export const patchAgentEndpoint = (
  endpoint: AgentEndpoint,
  body: { enabled?: boolean; name?: string; rotateCredential?: boolean; rateLimit?: unknown },
) => api.patch<{ endpoint: AgentEndpoint; credential?: string }>(
  `/api/v1/agent-endpoints/${encodeURIComponent(endpoint.id)}`,
  { ...body, version: endpoint.version },
);

export async function invokeAgentEndpoint(
  endpoint: AgentEndpoint,
  credential: string,
  body: { message?: string; sessionId?: string; title?: string; description?: string; input?: unknown },
) {
  const suffix = endpoint.invocationMode === 'conversation' ? 'conversations' : 'jobs';
  const response = await fetch(`/invoke/v1/endpoints/${encodeURIComponent(endpoint.slug)}/${suffix}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': credential,
      ...(endpoint.invocationMode === 'job' ? { 'Idempotency-Key': crypto.randomUUID() } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await readApiError(response, 'Endpoint invocation failed');
  return response.json() as Promise<Record<string, unknown>>;
}
