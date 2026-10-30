import { describe, expect, it } from 'vitest';
import { isAgentTurnOutcome, type AgentSessionEvent } from './agentSessions';

describe('logical turn outcomes', () => {
  const event = (type: string, turn = 't'): AgentSessionEvent => ({
    type, session_id: 's', data: { turn_id: turn, run_id: 'r', status: 'completed' },
  });
  it('does not finish a turn when one execution ends', () => {
    for (const type of ['run.started', 'run.ended', 'turn.ended', 'turn.running', 'required_action.created'])
      expect(isAgentTurnOutcome(event(type), 't')).toBe(false);
  });
  it('waits for the matching durable command outcome', () => {
    for (const type of ['turn.completed', 'turn.failed', 'turn.cancelled', 'turn.interrupted', 'turn.requires_action']) {
      expect(isAgentTurnOutcome(event(type), 't')).toBe(true);
      expect(isAgentTurnOutcome(event(type, 'other'), 't')).toBe(false);
    }
  });
});
