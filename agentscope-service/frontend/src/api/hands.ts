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
