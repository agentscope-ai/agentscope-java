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
