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

import { describe, expect, it } from 'vitest';
import type { Endpoint } from '@/api/agentEndpoints';
import { defaultEndpointOwnerPath, safeEndpointOwnerPath } from './endpointNavigation';

const endpoint = (targetType: Endpoint['targetType'], targetRef = 'target-1') => ({
  targetType,
  targetRef,
} as Endpoint);

describe('Endpoint owner navigation', () => {
  it('returns to the concrete Agent or Team owner', () => {
    expect(defaultEndpointOwnerPath(endpoint('agent'))).toBe('/agent-center/agents/target-1?tab=entrypoints');
    expect(defaultEndpointOwnerPath(endpoint('team'))).toBe('/agent-center/teams/target-1?tab=endpoints');
  });

  it('uses the caller-provided Workflow owner path for revision endpoints', () => {
    expect(safeEndpointOwnerPath('/agent-center/workflows/workflow-1', endpoint('orchestration_revision')))
      .toBe('/agent-center/workflows/workflow-1');
  });

  it('rejects return paths outside Agent Center owners', () => {
    expect(safeEndpointOwnerPath('https://example.com', endpoint('agent')))
      .toBe('/agent-center/agents/target-1?tab=entrypoints');
    expect(safeEndpointOwnerPath('/work/issues/secret', endpoint('team')))
      .toBe('/agent-center/teams/target-1?tab=endpoints');
  });
});
