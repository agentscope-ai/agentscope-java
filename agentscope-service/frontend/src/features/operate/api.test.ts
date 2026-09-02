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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { fetchOptional } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchOptional status control flow', () => {
  it('returns null for an optional endpoint that responds with 404', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ error: 'inventory is unavailable' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    )));

    await expect(fetchOptional('/api/v1/agents/example/subagents')).resolves.toBeNull();
  });

  it('still rejects non-optional status codes with the shared ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));

    const promise = fetchOptional('/api/v1/agents/example/subagents');

    await expect(promise).rejects.toBeInstanceOf(ApiError);
    await expect(promise).rejects.toMatchObject({ source: 'fallback', status: 500 });
  });
});
