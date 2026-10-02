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

export function endpointSlug(value: string): string {
  return value.toLowerCase().trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
}

export function nextEndpointSlug(base: string, existingSlugs: Iterable<string>): string {
  const normalized = endpointSlug(base) || 'endpoint';
  const existing = new Set(Array.from(existingSlugs, value => value.toLowerCase()));
  if (!existing.has(normalized)) return normalized;
  for (let suffix = 2; ; suffix += 1) {
    const ending = `-${suffix}`;
    const candidate = `${normalized.slice(0, 48 - ending.length).replace(/-+$/g, '')}${ending}`;
    if (!existing.has(candidate)) return candidate;
  }
}

export function nextEndpointName(base: string, existingNames: Iterable<string>): string {
  const normalized = base.trim() || 'Endpoint';
  const existing = new Set(Array.from(existingNames, value => value.trim().toLowerCase()));
  if (!existing.has(normalized.toLowerCase())) return normalized;
  for (let suffix = 2; ; suffix += 1) {
    const candidate = `${normalized} ${suffix}`;
    if (!existing.has(candidate.toLowerCase())) return candidate;
  }
}

export function endpointErrorMessage(cause: unknown, fallback: string): string {
  const message = cause instanceof Error ? cause.message : fallback;
  try {
    const body = JSON.parse(message) as { error?: unknown; message?: unknown };
    if (typeof body.error === 'string' && body.error) return body.error;
    if (typeof body.message === 'string' && body.message) return body.message;
  } catch {
    // The message is already plain text.
  }
  return message || fallback;
}
