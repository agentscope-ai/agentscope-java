// Auth API for the admin console.
// Login is delegated to agentscope-claw's /api/auth/login endpoint.
// The CLAW_URL is injected at build time via Vite's define or falls back to localStorage.
//
// To configure the claw URL, set VITE_CLAW_URL at build time:
//   VITE_CLAW_URL=http://localhost:8080 npm run build

const CLAW_URL: string = (() => {
  const buildTime = import.meta.env.VITE_CLAW_URL as string | undefined;
  if (buildTime && buildTime.length > 0) return buildTime.replace(/\/$/, '');
  const stored = localStorage.getItem('claw_url');
  if (stored) return stored.replace(/\/$/, '');
  // Default: assume claw runs on port 8080 (same host)
  return `${window.location.protocol}//${window.location.hostname}:8080`;
})();

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  username: string;
  roles: string[];
}

/**
 * Authenticates against agentscope-claw's /api/auth/login.
 * Only admin users can use the admin console — non-admin tokens will be rejected
 * by claw-web's SecurityConfig.
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${CLAW_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('Invalid credentials');
  const data: LoginResponse = await res.json();
  // Validate admin role before accepting the token
  if (!data.roles || !data.roles.some(r => r.toLowerCase() === 'admin')) {
    throw new Error('Access denied: admin role required');
  }
  return data;
}

export async function me(): Promise<MeResponse> {
  const res = await fetch(`${CLAW_URL}/api/auth/me`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('claw_token')}` },
  });
  if (!res.ok) throw new Error('Unauthorized');
  return res.json();
}

export function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

export function saveToken(token: string) {
  localStorage.setItem('claw_token', token);
}

export function clearToken() {
  localStorage.removeItem('claw_token');
}

export function clawUrl(): string {
  return CLAW_URL;
}
