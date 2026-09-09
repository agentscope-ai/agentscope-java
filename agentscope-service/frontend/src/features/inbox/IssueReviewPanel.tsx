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

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, MessageSquare } from "lucide-react";
import { acceptIssue, getIssue, rejectIssue, type InboxItem } from "@/api/collaboration";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/apiClient";
import { Textarea } from "@/components/ui/input";

function reviewErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return "You do not have permission to review this issue.";
  if (error instanceof ApiError && error.status === 409) return "This issue changed or is no longer awaiting review. Check the latest result before trying again.";
  if (!(error instanceof Error)) return "Review could not be saved.";
  try {
    const body = JSON.parse(error.message) as { error?: string };
    return typeof body.error === "string" ? body.error : error.message;
  } catch { return error.message; }
}

// Inbox decisions act on the notified Issue, never a child opened in its preview.
export default function IssueReviewPanel({ item, note, onNoteChange }: {
  item: InboxItem;
  note: string;
  onNoteChange: (note: string) => void;
}) {
  const qc = useQueryClient();
  const issueId = item.issueId!;
  const [editing, setEditing] = useState(false);
  const [outcome, setOutcome] = useState("");
  const issue = useQuery({ queryKey: ["issue", issueId], queryFn: () => getIssue(issueId), refetchInterval: 5000 });
  const current = issue.data?.issue;
  const actionable = !issue.isError && item.needsAction && !item.resolvedAt && !item.archived && current?.status === "in_review" && !current.archivedAt;
  const refresh = () => Promise.all([
    ...["issue", "issue-summary", "issue-activity"].map(key => qc.invalidateQueries({ queryKey: [key, issueId] })),
    ...["issues", "inbox", "inbox-summary", "inbox-item"].map(key => qc.invalidateQueries({ queryKey: [key] })),
  ]);
  const decision = useMutation({
    mutationFn: async (action: "accept" | "reject") => {
      if (!actionable || !current) throw new Error("This issue is no longer awaiting your review. Refresh to see its current status.");
      if (action === "reject" && !note.trim()) throw new Error("Describe the changes needed before returning this work.");
      // Submit the version shown to the reviewer. Never silently accept a newer revision.
      return action === "accept" ? acceptIssue(issueId, current.version) : rejectIssue(issueId, current.version, note.trim());
    },
    onSuccess: async (data, action) => {
      qc.setQueryData(["issue", issueId], data);
      setOutcome(action === "accept" ? "Result accepted. This issue is Done." : "Changes requested. This issue is back In progress; your review is recorded in Activity.");
      setEditing(false);
      onNoteChange("");
      await refresh();
    },
    onError: () => { void refresh(); },
  });
  const busy = decision.isPending;
  return <section aria-label="Issue review" className="max-h-[60%] shrink-0 space-y-3 overflow-y-auto border-b border-indigo-100 bg-indigo-50/60 px-5 py-4">
    <div className="flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
      <div className="min-w-0 flex-1"><h2 className="text-sm font-semibold text-slate-900">Review result</h2>
        <p className="mt-1 text-xs leading-5 text-slate-600">{actionable ? "Review the result and sub-issues below, then accept it or describe the changes needed. Resolving a comment does not accept the work." : issue.isPending ? "Loading current review status…" : current?.archivedAt ? "This issue is archived. Review is unavailable." : current?.status === "done" ? "This issue has already been accepted." : current?.status === "in_review" ? "This notification is no longer actionable. Open the latest review request." : current ? `This issue is ${current.status.replace(/_/g, " ")}; it is not awaiting acceptance.` : "Review status is unavailable."}</p>
      </div>
      {actionable && <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={busy || editing} onClick={() => decision.mutate("accept")}><Check className="h-4 w-4" />Accept result</Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => { decision.reset(); setEditing(true); }}><MessageSquare className="h-4 w-4" />Request changes</Button>
      </div>}
    </div>
    {editing && actionable && <form className="space-y-2" onSubmit={event => { event.preventDefault(); decision.mutate("reject"); }}>
      <label className="block text-xs font-medium text-slate-700" htmlFor={`review-note-${item.id}`}>Changes needed (required)</label>
      <Textarea id={`review-note-${item.id}`} autoFocus required maxLength={10000} disabled={busy} className="min-h-24 max-h-40 bg-white" value={note} onChange={event => onNoteChange(event.target.value)} placeholder="Describe what is missing and what would make the result acceptable…" />
      <p className="text-xs text-slate-500">Returns the issue to In progress and records this feedback. It does not automatically start another execution.</p>
      <div className="flex gap-2"><Button size="sm" type="submit" disabled={busy || !note.trim()}>{busy ? "Submitting…" : "Send review"}</Button><Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => setEditing(false)}>Cancel</Button></div>
    </form>}
    {outcome && <p role="status" className="text-sm text-emerald-800">{outcome}</p>}
    {(decision.isError || issue.isError) && <div role="alert" className="text-sm text-red-700"><p>{decision.isError ? reviewErrorMessage(decision.error) : "Unable to load the issue. Review actions are unavailable."}</p><p className="mt-1 text-xs">Check the current result and acceptance criteria before trying again.</p><Button size="sm" variant="ghost" disabled={busy} onClick={() => void refresh()}>Refresh review</Button></div>}
  </section>;
}
