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
import type { AgentDefinition } from "@/api/agents";
import {
  definitionFormPatch,
  type DefinitionFormValues,
} from "./agentDefinitionForm";

const agent = { name: "MA1" } as AgentDefinition;
const values: DefinitionFormValues = {
  name: "Changed",
  description: "New description",
  model: "",
  system: "",
  maxIters: "20",
  workspaceId: "",
  defaultEnvironmentId: "",
  defaultVaultIds: [],
  defaultMemoryStoreIds: [],
  version: 4,
};

describe("Independent definition editors", () => {
  it("saves behavior without clearing workspace or runtime resource bindings", () => {
    expect(definitionFormPatch(agent, "behavior", values)).toEqual({
      name: "MA1",
      version: 4,
      model: "",
      system: "",
      maxIters: 20,
    });
  });
  it("allows explicit unlinking without resubmitting stale behavior fields", () => {
    expect(definitionFormPatch(agent, "workspace", values)).toEqual({
      name: "MA1",
      version: 4,
      workspaceId: "",
    });
  });
  it("saves resource defaults independently of identity and behavior", () => {
    expect(definitionFormPatch(agent, "runtime", values)).toEqual({
      name: "MA1",
      version: 4,
      defaultEnvironmentId: "",
      defaultVaultIds: [],
      defaultMemoryStoreIds: [],
    });
  });
  it("saves metadata without touching the model or prompt", () => {
    expect(definitionFormPatch(agent, "settings", values)).toEqual({
      name: "Changed",
      description: "New description",
      version: 4,
    });
  });
  it.each(["0", "65", "1.5", "invalid"])(
    "rejects invalid iteration count %s",
    (maxIters) => {
      expect(() =>
        definitionFormPatch(agent, "behavior", { ...values, maxIters }),
      ).toThrow("Max iterations");
    },
  );
  it("keeps optimistic locking mandatory", () => {
    expect(() =>
      definitionFormPatch(agent, "workspace", {
        ...values,
        version: undefined,
      }),
    ).toThrow("version");
  });
});
