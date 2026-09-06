import { describe, expect, it } from 'vitest';
import type { EndpointCredential } from '@/api/agentEndpoints';
import { selectEndpointTestCredential } from './endpointTestCredential';

const credential = (
  id: string,
  overrides: Partial<EndpointCredential> = {},
): EndpointCredential => ({
  id,
  endpointId: 'endpoint-1',
  name: id,
  keyPrefix: id,
  recoverable: true,
  status: 'active',
  createdAt: '2026-01-01T00:00:00Z',
  ...overrides,
});

describe('selectEndpointTestCredential', () => {
  it('selects the first active recoverable credential', () => {
    const selected = selectEndpointTestCredential([
      credential('revoked', { status: 'revoked' }),
      credential('legacy', { recoverable: false }),
      credential('ready'),
    ]);
    expect(selected?.id).toBe('ready');
  });

  it('skips expired credentials', () => {
    const selected = selectEndpointTestCredential([
      credential('expired', { expiresAt: '2026-01-01T00:00:00Z' }),
      credential('valid', { expiresAt: '2027-01-01T00:00:00Z' }),
    ], Date.parse('2026-06-01T00:00:00Z'));
    expect(selected?.id).toBe('valid');
  });

  it('returns undefined when automatic reveal is unavailable', () => {
    expect(selectEndpointTestCredential([
      credential('legacy', { recoverable: false }),
      credential('expired', { expiresAt: '2025-01-01T00:00:00Z' }),
    ], Date.parse('2026-01-01T00:00:00Z'))).toBeUndefined();
  });
});
