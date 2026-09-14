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

import { filtersForIssueSource, issueSourceFromParam } from "./issueSource";

describe("issue source", () => {
  it("defaults to all sources", () => {
    expect(issueSourceFromParam(null)).toBe("all");
    expect(issueSourceFromParam("unknown")).toBe("all");
    expect(filtersForIssueSource("all")).toEqual({ includeOperational: true });
  });

  it("keeps the current source filters", () => {
    expect(filtersForIssueSource(issueSourceFromParam("work"))).toEqual({});
    expect(filtersForIssueSource(issueSourceFromParam("endpoint_jobs"))).toEqual({
      kind: "endpoint_job",
      includeOperational: true,
    });
  });
});
