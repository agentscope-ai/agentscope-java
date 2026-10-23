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

import {
  managedToolApprovalExpiry,
  managedToolApprovalRequest,
} from "./managedToolApproval";

describe("managedToolApprovalRequest", () => {
  const request = {
    kind: "managed_tool_confirmation",
    schemaVersion: 1,
    sessionId: "managed-session",
    sessionRef: "session-ref",
    agentTaskId: "task-id",
    attemptId: "attempt-id",
    dispatchGeneration: 2,
    turnId: "turn-id",
    toolUseId: "tool-use-id",
    toolName: "web_search",
    inputPreview: { query: "AgentScope" },
    expiresAt: "2026-09-06T12:00:00Z",
  };

  it("accepts the complete fenced request", () => {
    expect(managedToolApprovalRequest(request)).toEqual(request);
  });

  it("rejects an unfenced or unrelated approval", () => {
    expect(managedToolApprovalRequest({ ...request, dispatchGeneration: 0 })).toBeUndefined();
    expect(managedToolApprovalRequest({ ...request, attemptId: "" })).toBeUndefined();
    expect(managedToolApprovalRequest({ ...request, schemaVersion: 2 })).toBeUndefined();
    expect(managedToolApprovalRequest({ ...request, kind: "run_node" })).toBeUndefined();
  });

  it("accepts hosted and external runtime approval envelopes", () => {
    expect(managedToolApprovalRequest({
      ...request,
      kind: "runtime_tool_confirmation",
      backendKind: "hosted-runtime",
    })?.backendKind).toBe("hosted-runtime");
    expect(managedToolApprovalRequest({
      ...request,
      kind: "runtime_tool_confirmation",
      backendKind: "external-application",
    })?.backendKind).toBe("external-application");
    expect(managedToolApprovalRequest({
      ...request,
      kind: "runtime_tool_confirmation",
      backendKind: "managed",
    })).toBeUndefined();
  });

  it("normalizes RFC3339 and epoch-millis expiry values", () => {
    const parsed = managedToolApprovalRequest(request);
    expect(managedToolApprovalExpiry(parsed)).toBe(Date.parse(request.expiresAt));
    expect(
      managedToolApprovalExpiry(
        managedToolApprovalRequest({ ...request, expiresAt: 1_788_692_400_000 }),
      ),
    ).toBe(1_788_692_400_000);
  });
});
