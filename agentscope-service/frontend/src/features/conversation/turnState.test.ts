import { describe, expect, it } from 'vitest';
import { finishesConversationTurn } from './turnState';

describe('conversation turn completion', () => {
  it('keeps streaming and intermediate results busy', () => {
    for (const eventType of ['assistant.delta', 'assistant.message', 'agent.tool_result', 'span.model_request_end', 'turn.started']) {
      expect(finishesConversationTurn({ eventType })).toBe(false);
    }
  });
  it('accepts terminal events from all conversation transports', () => {
    for (const eventType of ['turn.completed', 'turn.failed', 'turn.cancelled', 'endpoint.failed', 'session.status_idle', 'session.error']) {
      expect(finishesConversationTurn({ eventType })).toBe(true);
    }
  });
});
