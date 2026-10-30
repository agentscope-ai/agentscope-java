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
