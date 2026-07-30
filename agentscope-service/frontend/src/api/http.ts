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
