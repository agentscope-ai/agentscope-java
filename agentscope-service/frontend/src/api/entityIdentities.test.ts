import { describe, expect, it } from "vitest";

import { entityIdentityKey, normalizeEntityRef } from "./entityIdentities";

describe("entity identity references", () => {
  it("maps legacy endpoint actors onto their endpoint identity", () => {
    expect(normalizeEntityRef({ type: "human", ref: "endpoint:1234" })).toEqual({
      type: "endpoint",
      ref: "1234",
    });
  });

  it("uses the same canonical key for API aliases", () => {
    expect(entityIdentityKey({ type: "workflow_revision", ref: "abc" })).toBe(
      entityIdentityKey({ type: "orchestration_revision", ref: "abc" }),
    );
    expect(entityIdentityKey({ type: "system", ref: "orchestration-run:abc" })).toBe(
      entityIdentityKey({ type: "orchestration_run", ref: "abc" }),
    );
  });
});
