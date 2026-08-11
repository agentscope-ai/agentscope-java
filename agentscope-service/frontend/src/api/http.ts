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

import { getToken } from './auth';

/** Shared Authorization + JSON headers for control-plane calls. */
export function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

/**
 * Prefer control-plane `{"error":"..."}` or legacy `{"message":"..."}`;
 * fall back to status text.
 */
export async function readApiError(res: Response, fallback: string): Promise<Error> {
  const text = await res.text().catch(() => '');
  if (text) {
    try {
      const body = JSON.parse(text) as { error?: unknown; message?: unknown };
      if (typeof body.error === 'string' && body.error) return new Error(body.error);
      if (typeof body.message === 'string' && body.message) return new Error(body.message);
    } catch {
      if (text.length < 400) return new Error(text);
    }
  }
  return new Error(`${fallback} (${res.status})`);
}
