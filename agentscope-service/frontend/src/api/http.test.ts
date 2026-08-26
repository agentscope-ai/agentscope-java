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
import { ApiError } from './errors';
import { readApiError } from './http';

function failedResponse(body: BodyInit | null, status = 400): Response {
  return new Response(body, { status });
}

describe('readApiError', () => {
  it.each([
    ['error', '{"error":"server error"}', 'server error'],
    ['message', '{"message":"legacy message"}', 'legacy message'],
    ['plain text', '  raw backend detail  ', '  raw backend detail  '],
  ])('preserves %s response text', async (_kind, body, expected) => {
    const error = await readApiError(failedResponse(body, 422), 'Frontend fallback');

    expect(error).toBeInstanceOf(ApiError);
    expect(error.source).toBe('server');
    expect(error.status).toBe(422);
    expect(error.message).toBe(expected);
    if (_kind !== 'plain text') {
      expect(error.details).toEqual(JSON.parse(body));
    }
  });

  it.each([null, '', '   ', '{}', '{"error":"","message":""}'])(
    'marks an empty or message-less response as frontend fallback',
    async (body) => {
      const error = await readApiError(failedResponse(body, 503), 'Frontend fallback');

      expect(error.source).toBe('fallback');
      expect(error.status).toBe(503);
      expect(error.message).toBe('Frontend fallback (503)');
      if (body === '{}') {
        expect(error.details).toEqual({});
      }
    },
  );

  it('does not expose a long non-JSON response such as an HTML error page', async () => {
    const error = await readApiError(failedResponse('x'.repeat(400), 502), 'Gateway failed');

    expect(error.source).toBe('fallback');
    expect(error.message).toBe('Gateway failed (502)');
  });
});
