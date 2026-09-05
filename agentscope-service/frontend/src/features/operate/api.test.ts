import { describe, expect, it } from 'vitest';
import { agentSessionDetailPath, sessionDetailPath } from './api';

describe('sessionDetailPath', () => {
  it('uses the Agent-scoped detail route when ownership is known', () => {
    expect(agentSessionDetailPath('agent-id', { id: 'session-id' })).toBe(
      '/agent-center/agents/agent-id/sessions/session-id',
    );
  });

  it('opens the canonical Work Hub detail page for a stored session', () => {
    expect(sessionDetailPath({ id: 'store/id', sessionId: 'runtime-session' })).toBe(
      '/work/sessions/store%2Fid',
    );
  });

  it('preserves runtime lookup dimensions when no store id is available', () => {
    expect(
      sessionDetailPath({
        sessionId: 'runtime/id',
        agentName: 'paw agent',
        namespace: 'default',
      }),
    ).toBe(
      '/work/sessions/runtime%2Fid?agent=paw+agent&namespace=default',
    );
  });
});
