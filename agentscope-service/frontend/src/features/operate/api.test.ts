import { describe, expect, it } from 'vitest';
import { sessionDetailPath } from './api';

describe('sessionDetailPath', () => {
  it('opens the canonical Operations detail page for a stored session', () => {
    expect(sessionDetailPath({ id: 'store/id', sessionId: 'runtime-session' })).toBe(
      '/operations/sessions/store%2Fid',
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
      '/operations/sessions/runtime%2Fid?agent=paw+agent&namespace=default',
    );
  });
});
