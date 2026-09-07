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
