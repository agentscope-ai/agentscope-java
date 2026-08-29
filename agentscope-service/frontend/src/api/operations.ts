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
