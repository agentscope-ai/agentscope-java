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

export type ApiErrorSource = 'server' | 'fallback';

interface ApiErrorOptions {
  source: ApiErrorSource;
  status?: number;
  cause?: unknown;
  details?: unknown;
}

/** An API failure whose user-visible text has a known owner. */
export class ApiError extends Error {
  readonly source: ApiErrorSource;
  readonly status?: number;
  readonly cause?: unknown;
  readonly details?: unknown;

  constructor(message: string, options: ApiErrorOptions) {
    super(message);
    this.name = 'ApiError';
    this.source = options.source;
    this.status = options.status;
    this.cause = options.cause;
    this.details = options.details;
  }
}

/** Marks text that came directly from a server response. */
export function serverApiError(message: string, status?: number, details?: unknown): ApiError {
  return new ApiError(message, { source: 'server', status, details });
}

/** Marks developer-owned fallback text that must be localized before display. */
export function fallbackApiError(
  message: string,
  status?: number,
  cause?: unknown,
  details?: unknown,
): ApiError {
  const statusSuffix = status === undefined ? '' : ` (${status})`;
  return new ApiError(`${message}${statusSuffix}`, {
    source: 'fallback',
    status,
    cause,
    details,
  });
}

function nonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/**
 * Reads a failed response without confusing an empty/unusable body with server-owned text.
 * Short non-JSON response text and JSON `error`/`message` values are preserved verbatim.
 */
export async function readApiError(res: Response, fallback: string): Promise<ApiError> {
  let readCause: unknown;
  const text = await res.text().catch((cause: unknown) => {
    readCause = cause;
    return '';
  });

  if (text.trim().length > 0) {
    let body: unknown;
    try {
      body = JSON.parse(text) as unknown;
    } catch {
      if (text.length < 400) {
        return serverApiError(text, res.status);
      }
      return fallbackApiError(fallback, res.status);
    }

    if (body !== null && typeof body === 'object') {
      const candidate = body as { error?: unknown; message?: unknown };
      if (nonBlankString(candidate.error)) {
        return serverApiError(candidate.error, res.status, body);
      }
      if (nonBlankString(candidate.message)) {
        return serverApiError(candidate.message, res.status, body);
      }
      return fallbackApiError(fallback, res.status, undefined, body);
    }
  }

  return fallbackApiError(fallback, res.status, readCause);
}

/** Resolves an API failure into safe user-visible text supplied by the current locale. */
export function resolveApiErrorMessage(error: unknown, localizedFallback: string): string {
  if (error instanceof ApiError && error.source === 'server' && error.message.trim().length > 0) {
    return error.message;
  }

  const status = error instanceof ApiError ? error.status : undefined;
  return status === undefined ? localizedFallback : `${localizedFallback} (${status})`;
}
