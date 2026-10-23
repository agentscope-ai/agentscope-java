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

import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, apiResponse } from "@/lib/apiClient";
import { addComment, assignIssue, downloadIssueArtifact, listIssueTasks } from "./collaboration";

vi.mock("@/lib/apiClient", () => ({ api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn(), apiResponse: vi.fn() }));
describe("Issue execution history", () => {
  beforeEach(() => vi.resetAllMocks());
  it("loads all pages with the Issue and scope filters on every request", async () => {
    const first = Array.from({ length: 100 }, (_, i) => ({ id: String(i), issueId: "child" }));
    vi.mocked(api.get).mockResolvedValueOnce({ items: first }).mockResolvedValueOnce({ items: [{ id: "last", issueId: "child" }] });
    const result = await listIssueTasks("tenant", "namespace", "child");
    expect(result.items).toHaveLength(101);
    expect(result.items[result.items.length - 1]?.id).toBe("last");
    const urls = vi.mocked(api.get).mock.calls.map(([path]) => new URL(path, "http://localhost"));
    expect(urls.map(url => url.searchParams.get("offset") || "0")).toEqual(["0", "100"]);
    for (const url of urls) {
      expect(url.searchParams.get("issueId")).toBe("child");
      expect(url.searchParams.get("tenant")).toBe("tenant");
      expect(url.searchParams.get("namespace")).toBe("namespace");
    }
  });
  it("returns an empty Issue history without retrying", async () => {
    vi.mocked(api.get).mockResolvedValue({ items: [] });
    expect((await listIssueTasks("t", "n", "empty")).items).toEqual([]);
    expect(api.get).toHaveBeenCalledTimes(1);
  });
});


describe("Issue property commands", () => {
  beforeEach(() => vi.resetAllMocks());
  it("clears both assignee fields with an optimistic version", async () => {
    await assignIssue("issue-id", "", "", 7);
    expect(api.post).toHaveBeenCalledWith("/api/v1/issues/issue-id/assign", { assigneeType: "", assigneeRef: "", expectedVersion: 7 });
  });
  it("downloads binary bytes through the authenticated response client", async () => {
    const bytes = new Uint8Array([0, 128, 255, 10]);
    vi.mocked(apiResponse).mockResolvedValue(new Response(bytes, { headers: { "Content-Type": "application/octet-stream" } }));
    const blob = await downloadIssueArtifact({ id: "artifact/id", filename: "test.bin" });
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(bytes);
    expect(apiResponse).toHaveBeenCalledWith("/api/v1/artifacts/artifact%2Fid/download", { method: "POST" });
  });
  it("propagates download errors", async () => {
    vi.mocked(apiResponse).mockRejectedValue(new Error("artifact has expired"));
    await expect(downloadIssueArtifact({ id: "expired", filename: "test.bin" })).rejects.toThrow("artifact has expired");
  });
  it("publishes a result separately from general discussion", async () => {
    await addComment("issue-id", "Verified", undefined, [], "result");
    expect(api.post).toHaveBeenCalledWith("/api/v1/issues/issue-id/comments", { content: "Verified", parentId: undefined, mentions: [], type: "result" });
  });
});
