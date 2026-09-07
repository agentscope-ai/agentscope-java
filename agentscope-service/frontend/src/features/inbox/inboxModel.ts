import type { InboxItem } from "@/api/collaboration";

export const INBOX_TYPES: Record<string, string> = {
  approval: "Approvals", review_request: "Review requests", issue_blocked: "Blocked work",
  mention: "Mentions", reply: "Replies", result: "Agent results", issue_update: "Issue updates",
  issue_completed: "Completed work", issue_cancelled: "Cancelled work", issue_reopened: "Resumed work",
  issue_assigned: "Assignments", routing_blocked: "Routing alerts", agent_task_failed: "Execution failures",
  dead_letter: "Delivery failures", outbox_dead_letter: "Event delivery alerts", issue_sla_breached: "Overdue work",
};

export function inboxTypeLabel(item: InboxItem): string {
  if (item.approvalId) return item.needsAction ? "Pending approval" : "Approval";
  if (item.type === "review_request") return item.needsAction ? "Needs review" : "Review request";
  if (item.type === "issue_blocked") return item.resolvedAt ? "Blocker resolved" : "Blocked";
  return INBOX_TYPES[item.type] || item.type.replace(/_/g, " ");
}

// Reading the selected item must not remove its detail or shift to another row.
export function retainInboxSelection(items: InboxItem[], selected: InboxItem | undefined, index: number): InboxItem[] {
  const unique = [...new Map(items.map(item => [item.id, item])).values()];
  if (!selected) return unique;
  const position = unique.findIndex(item => item.id === selected.id);
  if (position >= 0) unique[position] = selected;
  else unique.splice(Math.max(0, Math.min(index, unique.length)), 0, selected);
  return unique;
}
