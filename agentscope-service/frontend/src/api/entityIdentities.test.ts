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
