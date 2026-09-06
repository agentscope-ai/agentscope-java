import type { Approval, InboxItem } from '@/api/collaboration';

type ApprovalReference = Pick<Approval, 'id'>;
type InboxReference = Pick<InboxItem, 'approvalId' | 'archived' | 'read'>;

export interface ApprovalAttentionSummary {
  total: number;
  unread: number;
  pending: number;
}

/**
 * Counts everything requiring attention without counting the unread Inbox
 * notification for a pending Approval a second time.
 */
export function approvalAttentionSummary(
  inboxItems: InboxReference[],
  pendingApprovals: ApprovalReference[],
): ApprovalAttentionSummary {
  const pendingIds = new Set(pendingApprovals.map(approval => approval.id));
  const unreadItems = inboxItems.filter(item => !item.read && !item.archived);
  const unreadOutsidePendingApprovals = unreadItems.filter(
    item => !item.approvalId || !pendingIds.has(item.approvalId),
  );
  return {
    total: pendingApprovals.length + unreadOutsidePendingApprovals.length,
    unread: unreadItems.length,
    pending: pendingApprovals.length,
  };
}

export function formatAttentionCount(count: number): string {
  return count > 99 ? '99+' : String(count);
}
