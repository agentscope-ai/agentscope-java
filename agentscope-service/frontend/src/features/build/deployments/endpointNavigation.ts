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
