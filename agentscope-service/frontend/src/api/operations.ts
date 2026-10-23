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

import { api } from '@/lib/apiClient';

export interface DeadLetterEvent {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  attempts: number;
  lastError?: string;
  deadLetteredAt?: string;
}

export function listDeadLetters(tenant: string, namespace: string) {
  const params = new URLSearchParams({ tenant, namespace });
  return api.get<{ items: DeadLetterEvent[] }>(`/api/v1/dead-letters?${params}`);
}

export function replayDeadLetter(eventId: string) {
  return api.post<{ event: DeadLetterEvent }>(`/api/v1/dead-letters/${encodeURIComponent(eventId)}/replay`, {});
}
