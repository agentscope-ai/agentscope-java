import { getToken } from './auth';

export type HandsStatus = {
  brainInstanceId: string;
  pendingWorkItems: number;
  localSandboxRegistrySize: number;
  workerHeartbeats: Record<string, number>;
  sessionHandsMetrics: Record<string, { acquires: number; releases: number; timeouts: number }>;
};

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export async function fetchHandsStatus(): Promise<HandsStatus> {
  const res = await fetch('/api/hands/status', { headers: authHeaders() });
  if (!res.ok) {
    throw new Error(`hands status failed: ${res.status}`);
  }
  return res.json();
}
