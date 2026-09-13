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
  agentDetailPath,
  agentDetailTabs,
  agentServicePath,
  legacyAgentManagePath,
  resolveAgentDetailTab,
} from "./agentNavigation";

describe("Unified Agent navigation", () => {
  it.each(["managed", "external-application", "hosted-runtime"])(
    "opens %s at the same overview entrypoint",
    (runtimeKind) => {
      expect(agentDetailPath({ id: "agent/one", runtimeKind })).toBe(
        "/agent-center/agents/agent%2Fone",
      );
    },
  );
  it("exposes portable definitions for every runtime", () => {
    expect(agentDetailTabs("managed").map((tab) => tab.id)).toEqual([
      "overview",
      "activity",
      "definition",
      "runtime",
      "connections",
      "settings",
    ]);
    for (const type of ["hosted-runtime", "external-application"]) {
      expect(agentDetailTabs(type).map((tab) => tab.id)).toEqual([
        "overview",
        "activity",
        "definition",
        "runtime",
        "connections",
        "settings",
      ]);
    }
  });
  it("preserves legacy activity, session and API links", () => {
    expect(resolveAgentDetailTab("sessions", "managed")).toBe("activity");
    expect(resolveAgentDetailTab("related-work", "managed")).toBe("activity");
    expect(resolveAgentDetailTab("entrypoints", "managed")).toBe("connections");
    expect(resolveAgentDetailTab("runtime", "hosted-runtime")).toBe("runtime");
  });
  it("redirects legacy management sections without dropping scope", () => {
    expect(
      legacyAgentManagePath("a", "settings", "?tenant=t&namespace=n"),
    ).toBe("/agent-center/agents/a/definition/behavior?tenant=t&namespace=n");
    expect(legacyAgentManagePath("a", "skills")).toBe(
      "/agent-center/agents/a/definition/skills",
    );
    expect(legacyAgentManagePath("a", "channels")).toBe(
      "/agent-center/agents/a?tab=connections&connection=channels",
    );
    expect(legacyAgentManagePath("a", "sessions")).toBe(
      "/agent-center/agents/a?tab=activity&view=sessions",
    );
  });
  it("accepts portable definitions and rejects unknown tabs", () => {
    expect(resolveAgentDetailTab("definition", "external-application")).toBe(
      "definition",
    );
    expect(resolveAgentDetailTab("missing", "managed")).toBe("overview");
    expect(agentServicePath("a/b")).toBe("/agent-center/agents/a%2Fb");
  });
});
