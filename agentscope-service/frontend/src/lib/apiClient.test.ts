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
import {
  ApiError as CoreApiError,
  resolveApiErrorMessage,
} from '@/api/errors';
import {
  ApiError as CompatibleApiError,
  apiFetch,
  clearToken,
  getToken,
  saveToken,
} from './apiClient';

afterEach(() => {
  clearToken();
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

async function rejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
    throw new Error('Expected request to reject');
  } catch (error) {
    return error;
  }
}

describe('apiFetch errors', () => {
  it('compatibly re-exports the shared ApiError class', () => {
    expect(CompatibleApiError).toBe(CoreApiError);
  });

  it('preserves a structured server error and its control-flow details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ error: 'session is busy', code: 'busy', hint: 'force_confirm' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    )));

    const error = await rejection(apiFetch('/api/v1/sessions/one/compress'));

    expect(error).toBeInstanceOf(CoreApiError);
    expect(error).toMatchObject({
      source: 'server',
      status: 409,
      message: 'session is busy',
      details: { error: 'session is busy', code: 'busy', hint: 'force_confirm' },
    });
  });

  it('does not expose an empty-body statusText fallback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', {
      status: 503,
      statusText: 'Service Unavailable',
    })));

    const error = await rejection(apiFetch('/api/v1/overview'));

    expect(error).toMatchObject({ source: 'fallback', status: 503 });
    expect(resolveApiErrorMessage(error, '请求失败')).toBe('请求失败 (503)');
    expect(resolveApiErrorMessage(error, '请求失败')).not.toContain('Service Unavailable');
  });

  it('marks automatic 401 handling as frontend fallback while retaining status', async () => {
    window.history.replaceState(null, '', '/login');
    saveToken('expired-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      'Unauthorized',
      { status: 401 },
    )));

    const error = await rejection(apiFetch('/api/v1/overview'));

    expect(getToken()).toBeNull();
    expect(error).toMatchObject({ source: 'fallback', status: 401 });
    expect(resolveApiErrorMessage(error, '登录已失效')).toBe('登录已失效 (401)');
  });

  it('keeps a network TypeError safe at the display boundary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const error = await rejection(apiFetch('/api/v1/overview'));

    expect(error).toBeInstanceOf(TypeError);
    expect(resolveApiErrorMessage(error, '网络请求失败')).toBe('网络请求失败');
  });

  it('keeps a successful-response JSON decoding error safe at the display boundary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      'not-json',
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )));

    const error = await rejection(apiFetch('/api/v1/overview'));

    expect(error).toBeInstanceOf(SyntaxError);
    expect(resolveApiErrorMessage(error, '响应解析失败')).toBe('响应解析失败');
  });
});
