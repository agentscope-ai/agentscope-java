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
