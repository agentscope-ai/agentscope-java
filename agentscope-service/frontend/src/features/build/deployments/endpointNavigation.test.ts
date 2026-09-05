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
