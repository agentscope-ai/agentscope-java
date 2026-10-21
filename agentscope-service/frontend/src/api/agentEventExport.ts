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
import { authHeaders, readApiError } from './http';
import type { AgentSessionEvent } from './agentSessions';

const fileLimit = 32 * 1024 * 1024;
const lineLimit = 1024 * 1024;

/** Only return a download after every window reaches the same committed watermark. */
export async function downloadAgentEvents(session: string, options: {
  through: string; turn?: string; signal: AbortSignal; onProgress?: (count: number) => void;
}): Promise<{ blob: Blob; count: number; through: string }> {
  if (!options.through) throw new Error('Refresh the Session before exporting its events.');
  const base = `/v1/sessions/${encodeURIComponent(session)}`
    + (options.turn ? `/turns/${encodeURIComponent(options.turn)}` : '');
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  const encoder = new TextEncoder();
  let after = '', size = 0, total = 0;
  while (true) {
    options.signal.throwIfAborted();
    const response = await fetch(`${base}/export?${new URLSearchParams({ after, through: options.through, limit: '1000' })}`, {
      headers: authHeaders(), signal: options.signal,
    });
    if (!response.ok) throw await readApiError(response, 'Event export failed');
    const next = response.headers.get('X-Next-Cursor');
    const more = response.headers.get('X-Has-More');
    const rawCount = response.headers.get('X-Export-Count');
    if (response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/x-ndjson'
      || response.headers.get('X-Export-Through') !== options.through || !next
      || !['true', 'false'].includes(more ?? '') || !rawCount || !/^\d+$/.test(rawCount)
      || Number(rawCount) > 1000 || !response.body) {
      await response.body?.cancel();
      throw new Error('Unsupported event export response.');
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let pending = '', count = 0, last = after;
    try {
      while (true) {
        options.signal.throwIfAborted();
        const chunk = await reader.read();
        if (chunk.value) {
          size += chunk.value.byteLength;
          if (size > fileLimit) throw new Error('Console export exceeds 32 MiB. Use the streaming Service SDK for a larger export.');
          chunks.push(chunk.value.slice());
        }
        pending += decoder.decode(chunk.value, { stream: !chunk.done });
        let newline: number;
        while ((newline = pending.indexOf('\n')) >= 0) {
          const line = pending.slice(0, newline); pending = pending.slice(newline + 1);
          if (encoder.encode(line).byteLength > lineLimit) throw new Error('Event exceeds the 1 MiB Console export limit. No file was saved.');
          const event = JSON.parse(line) as AgentSessionEvent;
          if (event.schema_version !== 1 || event.session_id !== session || typeof event.id !== 'string'
            || typeof event.cursor !== 'string' || !event.cursor || event.cursor === last || typeof event.type !== 'string'
            || !event.data || typeof event.data !== 'object' || Array.isArray(event.data)
            || (options.turn && event.data.turn_id !== options.turn)) throw new Error('Unsupported event export schema.');
          if (++count > Number(rawCount)) throw new Error('Event export count mismatch.');
          last = event.cursor;
        }
        if (encoder.encode(pending).byteLength > lineLimit) throw new Error('Event exceeds the 1 MiB Console export limit. No file was saved.');
        if (chunk.done) break;
      }
      if (pending || count !== Number(rawCount) || (more === 'true' && (!count || next !== last || next === after)))
        throw new Error('Incomplete event export. No file was saved.');
    } finally {
      await reader.cancel().catch(() => undefined); reader.releaseLock();
    }
    total += count; options.onProgress?.(total);
    if (more === 'false') {
      if (next !== options.through) throw new Error('Incomplete event export watermark. No file was saved.');
      options.signal.throwIfAborted();
      return { blob: new Blob(chunks, { type: 'application/x-ndjson' }), count: total, through: options.through };
    }
    after = next;
  }
}
