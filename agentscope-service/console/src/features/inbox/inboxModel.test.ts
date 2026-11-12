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

import { describe, expect, it } from "vitest";
import type { InboxItem } from "@/api/collaboration";
import { inboxTypeLabel, retainInboxSelection } from "./inboxModel";

const item = (id: string, rest: Partial<InboxItem> = {}): InboxItem => ({ id, type: "mention", severity: "info", actor: { type: "agent", ref: "worker" }, title: id, createdAt: "2026-09-07T08:00:00Z", read: false, archived: false, needsAction: false, ...rest });
describe("Inbox selection and action semantics", () => {
  it("keeps a read message in its original position when unread filtering removes it", () => {
    const selected = item("b", { read: true });
    expect(retainInboxSelection([item("a"), item("c")], selected, 1).map(entry => entry.id)).toEqual(["a", "b", "c"]);
    expect(retainInboxSelection([item("a"), item("c")], undefined, 1).map(entry => entry.id)).toEqual(["a", "c"]);
  });
  it("deduplicates pages and uses the selected item's current state", () => {
    const selected = item("b", { read: true });
    expect(retainInboxSelection([item("a"), item("b"), item("b")], selected, 1)).toEqual([item("a"), selected]);
  });
  it("marks read pending approvals and subscriber blockers without confusing read with resolved", () => {
    expect(inboxTypeLabel(item("a", { approvalId: "approval", needsAction: true, read: true }))).toBe("Pending approval");
    expect(inboxTypeLabel(item("a", { type: "issue_blocked", needsAction: false }))).toBe("Blocked");
    expect(inboxTypeLabel(item("a", { type: "issue_blocked", resolvedAt: "2026-09-07T09:00:00Z" }))).toBe("Blocker resolved");
  });
});
