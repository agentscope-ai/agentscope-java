/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { afterEach, expect, it, vi } from 'vitest';
import { downloadAgentEvents } from './agentEventExport';

vi.mock('./auth', () => ({ getToken: () => null }));
afterEach(() => vi.unstubAllGlobals());
const event = (cursor: string, session = 's') => ({ schema_version: 1, id: cursor, session_id: session,
  cursor, type: 'item.completed', data: { turn_id: 't', text: '中文 output' } });
function window(body: string | Uint8Array<ArrayBuffer>, more = false, next = 'end+/=', count = 1) {
  return new Response(body, { headers: { 'Content-Type': 'application/x-ndjson; charset=utf-8',
    'X-Export-Through': 'end+/=', 'X-Next-Cursor': next, 'X-Has-More': String(more), 'X-Export-Count': String(count) } });
}
const options = () => ({ through: 'end+/=', signal: new AbortController().signal });

it('collects only complete fixed-watermark windows and preserves UTF-8 split across chunks', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify(event('middle+/=')) + '\n');
  const first = window('', true, 'middle+/=');
  const split = new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += 3) controller.enqueue(bytes.slice(i, i + 3));
    controller.close();
  } });
  const send = vi.fn().mockResolvedValueOnce(new Response(split, { headers: first.headers }))
    .mockResolvedValueOnce(window(JSON.stringify(event('last')) + '\n'));
  vi.stubGlobal('fetch', send);
  const progress: number[] = [];
  const result = await downloadAgentEvents('s', { ...options(), turn: 't', onProgress: value => progress.push(value) });
  expect(result.count).toBe(2); expect(progress).toEqual([1, 2]);
  expect(await result.blob.text()).toBe(JSON.stringify(event('middle+/=')) + '\n' + JSON.stringify(event('last')) + '\n');
  const query = new URL(send.mock.calls[1][0], 'http://fixture');
  expect(query.pathname).toBe('/v1/sessions/s/turns/t/export');
  expect(query.searchParams.get('through')).toBe('end+/=');
  expect(query.searchParams.get('after')).toBe('middle+/=');
});

it.each([
  [JSON.stringify(event('c')), false, 'end+/=', 1],
  [JSON.stringify(event('c')) + '\n', false, 'end+/=', 2],
  [JSON.stringify(event('c', 'foreign')) + '\n', false, 'end+/=', 1],
  [JSON.stringify(event('c')) + '\n', true, 'wrong', 1],
  [JSON.stringify(event('c')) + '\n', false, 'wrong', 1],
] as const)('rejects incomplete, foreign or inconsistent export windows (%#)', async (body, more, next, count) => {
  vi.stubGlobal('fetch', vi.fn(async () => window(body, more, next, count)));
  await expect(downloadAgentEvents('s', options())).rejects.toThrow();
});

it('bounds total bytes before decoding and performs no request after cancellation', async () => {
  const send = vi.fn(async () => window(new Uint8Array(32 * 1024 * 1024 + 1)));
  vi.stubGlobal('fetch', send);
  await expect(downloadAgentEvents('s', options())).rejects.toThrow('32 MiB');
  const controller = new AbortController(); controller.abort();
  await expect(downloadAgentEvents('s', { ...options(), signal: controller.signal })).rejects.toThrow();
  expect(send).toHaveBeenCalledTimes(1);
});
