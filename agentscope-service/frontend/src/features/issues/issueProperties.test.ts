import { describe, expect, it } from "vitest";
import { dateTimeLocal, dueDatePatch, parseCriteria, sourceWebUrl } from "./issueProperties";

describe("Issue properties", () => {
  it("round trips a local deadline and explicitly clears it", () => {
    const timestamp = "2026-09-10T09:30:00.000Z";
    expect(dueDatePatch(dateTimeLocal(timestamp))).toEqual({ dueAt: timestamp });
    expect(dueDatePatch("")).toEqual({ dueAt: null });
    expect(dateTimeLocal(undefined)).toBe("");
    expect(dateTimeLocal("invalid")).toBe("");
  });

  it("reads legacy empty criteria and preserves extension fields", () => {
    expect(parseCriteria([])).toEqual({});
    expect(parseCriteria(null)).toEqual({});
    expect(parseCriteria({ checklist: null, customRule: "keep" })).toEqual({ checklist: [], customRule: "keep" });
    const value = { checklist: [{ id: "a", text: "A", required: false, satisfied: false, evidence: "ref" }], customRule: { version: 2 } };
    expect(parseCriteria(value)).toEqual(value);
    expect(parseCriteria({ checklist: [null, "invalid", { satisfied: true }] }).checklist).toEqual([{ satisfied: true }]);
  });

  it("opens web sources while leaving opaque or unsafe references as text", () => {
    expect(sourceWebUrl("https://github.com/owner/repo/issues/123")).toBe("https://github.com/owner/repo/issues/123");
    for (const ref of [undefined, "owner/repo#123", "javascript:alert(1)", "data:text/html,hello", "https://user:secret@example.com", "//example.com"]) expect(sourceWebUrl(ref)).toBeUndefined();
  });
});
