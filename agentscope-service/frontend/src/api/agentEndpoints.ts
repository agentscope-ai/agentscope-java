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

import { api, getToken } from '@/lib/apiClient';
import { readApiError } from '@/api/http';

export type EndpointTargetType = 'agent' | 'team' | 'orchestration_revision';
export type EndpointInvocationMode = 'conversation' | 'job';
export type EndpointStatus = 'draft' | 'published' | 'disabled' | 'archived';
export type EndpointInvocationStatus =
  | 'accepted' | 'dispatching' | 'running' | 'waiting'
  | 'completed' | 'failed' | 'cancelled' | 'timed_out';

export interface Endpoint {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  slug: string;
  description?: string;
  targetType: EndpointTargetType;
  targetRef: string;
  invocationMode: EndpointInvocationMode;
  inputSchema?: unknown;
  outputSchema?: unknown;
  eventSchemaVersion: string;
  timeoutSeconds: number;
  maxPayloadBytes: number;
  authPolicy: { type?: 'api_key' | 'platform' };
  rateLimit?: { requests?: number; windowSeconds?: number };
  activeReleaseId?: string;
  activeRelease?: number;
  status: EndpointStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt?: string;
}

export interface EndpointRelease {
  id: string;
  endpointId: string;
  number: number;
  targetType: EndpointTargetType;
  targetRef: string;
  createdBy?: { type?: string; id?: string; name?: string };
  reason?: string;
  createdAt: string;
  activatedAt: string;
}

export interface EndpointCredential {
  id: string;
  endpointId: string;
  name: string;
  keyPrefix: string;
  recoverable?: boolean;
  status: 'active' | 'revoked' | 'expired';
  scopes?: unknown;
  expiresAt?: string;
  lastUsedAt?: string;
  rotatedFrom?: string;
  createdAt: string;
  revokedAt?: string;
}

export interface EndpointInvocation {
  id: string;
  endpointId: string;
  mode: EndpointInvocationMode;
  status: EndpointInvocationStatus;
  conversationId?: string;
  turnId?: string;
  sessionId?: string;
  issueId?: string;
  runId?: string;
  errorCode?: string;
  errorMessage?: string;
  correlationId: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  updatedAt: string;
}

export interface EndpointReadiness {
  state: string;
  reason: string;
  compatible: boolean;
}

export interface CreateEndpointRequest {
  tenant?: string;
  namespace?: string;
  name: string;
  slug: string;
  description?: string;
  targetType: EndpointTargetType;
  targetRef: string;
  invocationMode: EndpointInvocationMode;
  authPolicy?: { type: 'api_key' | 'platform' };
  rateLimit?: { requests: number; windowSeconds: number };
  inputSchema?: unknown;
  outputSchema?: unknown;
  timeoutSeconds?: number;
  maxPayloadBytes?: number;
}

export const listEndpoints = (tenant = 'default', namespace = 'default', filter?: { targetType?: EndpointTargetType; targetRef?: string }) => {
  const params = new URLSearchParams({ tenant, namespace });
  if (filter?.targetType) params.set('targetType', filter.targetType);
  if (filter?.targetRef) params.set('targetRef', filter.targetRef);
  return api.get<{ items: Endpoint[] }>(`/api/v1/endpoints?${params}`);
};

export const getEndpoint = (endpointId: string) =>
  api.get<{ endpoint: Endpoint }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}`);

export const createEndpoint = (body: CreateEndpointRequest) =>
  api.post<{ endpoint: Endpoint; credential?: string; credentialResource?: EndpointCredential }>('/api/v1/endpoints', body);

export const patchEndpoint = (endpoint: Endpoint, body: Partial<Pick<Endpoint,
  'name' | 'description' | 'inputSchema' | 'outputSchema' | 'rateLimit' | 'timeoutSeconds' | 'maxPayloadBytes'>>) =>
  api.patch<{ endpoint: Endpoint }>(`/api/v1/endpoints/${encodeURIComponent(endpoint.id)}`, { ...body, version: endpoint.version });

export const getEndpointReadiness = (endpointId: string) =>
  api.get<{ readiness: EndpointReadiness }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/readiness`);

export const publishEndpoint = (endpoint: Endpoint) =>
  api.post<{ endpoint: Endpoint; readiness: EndpointReadiness }>(`/api/v1/endpoints/${encodeURIComponent(endpoint.id)}/publish`, { version: endpoint.version });

export const listEndpointReleases = (endpointId: string) =>
  api.get<{ items: EndpointRelease[] }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/releases`);

export const deployEndpointRelease = (endpoint: Endpoint, targetRef: string, reason?: string) =>
  api.post<{ endpoint: Endpoint; release: EndpointRelease; readiness: EndpointReadiness }>(
    `/api/v1/endpoints/${encodeURIComponent(endpoint.id)}/releases`,
    { targetRef, reason, version: endpoint.version },
  );

export const rollbackEndpointRelease = (endpoint: Endpoint, releaseId: string) =>
  api.post<{ endpoint: Endpoint; release: EndpointRelease; readiness: EndpointReadiness }>(
    `/api/v1/endpoints/${encodeURIComponent(endpoint.id)}/releases/${encodeURIComponent(releaseId)}/rollback`,
    { version: endpoint.version },
  );

export const disableEndpoint = (endpoint: Endpoint) =>
  api.post<{ endpoint: Endpoint }>(`/api/v1/endpoints/${encodeURIComponent(endpoint.id)}/disable`, { version: endpoint.version });

export const archiveEndpoint = (endpoint: Endpoint) =>
  api.delete<{ endpoint: Endpoint }>(`/api/v1/endpoints/${encodeURIComponent(endpoint.id)}`, { version: endpoint.version });

export const listEndpointCredentials = (endpointId: string) =>
  api.get<{ items: EndpointCredential[] }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/credentials`);

export const createEndpointCredential = (endpointId: string, body: { name: string; expiresAt?: string }) =>
  api.post<{ credential: EndpointCredential; secret: string }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/credentials`, body);

export const rotateEndpointCredential = (endpointId: string, credentialId: string) =>
  api.post<{ credential: EndpointCredential; secret: string }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/credentials/${encodeURIComponent(credentialId)}/rotate`, {});

export const revealEndpointCredential = (endpointId: string, credentialId: string) =>
  api.post<{ credentialId: string; secret: string }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/credentials/${encodeURIComponent(credentialId)}/reveal`, {});

export const revokeEndpointCredential = (endpointId: string, credentialId: string) =>
  api.delete<{ credential: EndpointCredential }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/credentials/${encodeURIComponent(credentialId)}`);

export const listEndpointInvocations = (endpointId: string, limit = 50) =>
  api.get<{ items: EndpointInvocation[] }>(`/api/v1/endpoints/${encodeURIComponent(endpointId)}/invocations?limit=${limit}`);

async function publicRequest(endpoint: Endpoint, path: string, credential: string, body?: unknown, idempotencyKey?: string) {
  const usesPlatformCredential = endpoint.authPolicy?.type === 'platform';
  const platformCredential = usesPlatformCredential ? getToken() : null;
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(usesPlatformCredential
        ? (platformCredential ? { Authorization: `Bearer ${platformCredential}` } : {})
        : { 'X-API-Key': credential }),
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) throw await readApiError(response, 'Endpoint invocation failed');
  return response.json() as Promise<Record<string, unknown>>;
}

export const invokeEndpointJob = (endpoint: Endpoint, credential: string, body: { title?: string; description?: string; input?: unknown }, idempotencyKey = crypto.randomUUID()) =>
  publicRequest(endpoint, `/invoke/v1/endpoints/${encodeURIComponent(endpoint.slug)}/jobs`, credential, body, idempotencyKey);

export const startEndpointConversation = (endpoint: Endpoint, credential: string, message: string, idempotencyKey = crypto.randomUUID()) =>
  publicRequest(endpoint, `/invoke/v1/endpoints/${encodeURIComponent(endpoint.slug)}/conversations`, credential, { message }, idempotencyKey);

export const continueEndpointConversation = (endpoint: Endpoint, conversationId: string, credential: string, message: string, idempotencyKey = crypto.randomUUID()) =>
  publicRequest(endpoint, `/invoke/v1/conversations/${encodeURIComponent(conversationId)}/turns`, credential, { message }, idempotencyKey);
