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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tryLocalDevSession, getToken } from './auth';
import { clearAccountIdentity, getAccountIdentity } from './accountIdentity';

describe('local development Console session', () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    });
    clearAccountIdentity();
  });
  afterEach(() => { vi.unstubAllGlobals(); clearAccountIdentity(); });

  it('initializes the development account without a login form', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      token: 'development-token', userId: 'local-developer', username: 'local-developer', roles: ['admin'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetch);
    expect(await tryLocalDevSession()).toBe(true);
    expect(fetch).toHaveBeenCalledWith('/api/auth/dev-session', { cache: 'no-store' });
    expect(getToken()).toBe('development-token');
    expect(getAccountIdentity(getToken())?.roles).toEqual(['admin']);
  });

  it('keeps the existing login flow in production', async () => {
    localStorage.setItem('claw_token', 'production-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 404 })));
    expect(await tryLocalDevSession()).toBe(false);
    expect(getToken()).toBe('production-token');
  });

  it('does not treat server errors as a development identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    await expect(tryLocalDevSession()).rejects.toThrow();
    expect(getToken()).toBeNull();
  });
});
