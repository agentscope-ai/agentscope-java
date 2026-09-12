import type { SessionEventItem } from '@/features/operate/api';

/** A message, tool result, or text delta can arrive while the turn is still running. */
export function finishesConversationTurn(event: Pick<SessionEventItem, 'eventType'>): boolean {
  return /^(turn|endpoint)\.(completed|failed|cancelled)$|^session\.(status_idle|status_terminated|interrupted|error)$/.test(
    (event.eventType || '').toLowerCase(),
  );
}
