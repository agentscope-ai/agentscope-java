import type { Endpoint } from '@/api/agentEndpoints';

export function defaultEndpointOwnerPath(endpoint?: Endpoint) {
  if (!endpoint) return '/agent-center/agents';
  if (endpoint.targetType === 'agent') return `/agent-center/agents/${endpoint.targetRef}?tab=entrypoints`;
  if (endpoint.targetType === 'team') return `/agent-center/teams/${endpoint.targetRef}?tab=endpoints`;
  return '/agent-center/workflows';
}

export function safeEndpointOwnerPath(candidate: string | null, endpoint?: Endpoint) {
  if (candidate && /^\/agent-center\/(agents|teams|workflows)(?:\/|\?|$)/.test(candidate)) return candidate;
  return defaultEndpointOwnerPath(endpoint);
}
