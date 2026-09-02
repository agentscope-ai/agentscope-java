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
import {
  ApiError,
  fallbackApiError,
  resolveApiErrorMessage,
  serverApiError,
} from './errors';

describe('API error display', () => {
  it('preserves server-owned text verbatim', () => {
    const error = serverApiError('后端返回的详细错误', 409);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.source).toBe('server');
    expect(error.status).toBe(409);
    expect(resolveApiErrorMessage(error, '保存失败')).toBe('后端返回的详细错误');
  });

  it('replaces frontend fallback text while retaining status and cause', () => {
    const cause = new TypeError('Failed to fetch');
    const error = fallbackApiError('Failed to save agent', 503, cause);

    expect(error.source).toBe('fallback');
    expect(error.message).toBe('Failed to save agent (503)');
    expect(error.cause).toBe(cause);
    expect(resolveApiErrorMessage(error, '保存智能体失败')).toBe('保存智能体失败 (503)');
  });

  it('does not expose an incorrectly empty server marker', () => {
    expect(resolveApiErrorMessage(serverApiError('', 500), '请求失败')).toBe(
      '请求失败 (500)',
    );
  });

  it.each([
    new TypeError('Failed to fetch'),
    new Error('Internal frontend detail'),
    'non-error rejection',
    undefined,
  ])('uses localized fallback for unmarked errors', (error) => {
    expect(resolveApiErrorMessage(error, '请求失败')).toBe('请求失败');
  });
});
