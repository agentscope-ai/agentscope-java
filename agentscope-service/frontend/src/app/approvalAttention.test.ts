import { describe, expect, it } from 'vitest';
import { approvalAttentionSummary, formatAttentionCount } from './approvalAttention';

describe('approvalAttentionSummary', () => {
  it('deduplicates unread notifications linked to pending approvals', () => {
    expect(approvalAttentionSummary([
      { approvalId: 'approval-1', read: false, archived: false },
      { read: false, archived: false },
      { approvalId: 'resolved', read: false, archived: false },
      { read: false, archived: true },
      { read: true, archived: false },
    ], [
      { id: 'approval-1' },
      { id: 'approval-2' },
    ])).toEqual({ total: 4, unread: 3, pending: 2 });
  });

  it('caps the compact menu label', () => {
    expect(formatAttentionCount(12)).toBe('12');
    expect(formatAttentionCount(120)).toBe('99+');
  });
});
