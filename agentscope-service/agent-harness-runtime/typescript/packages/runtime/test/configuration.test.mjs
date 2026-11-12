import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { decodeConfiguration } from "../dist/configuration.js";
import { canonicalHash, TurnRejected } from "../dist/index.js";

const fixture = JSON.parse(readFileSync(new URL("../../../../protocol/examples/runtime-operations.json", import.meta.url))).configurationResponse;
function seal(value) {
  delete value.identity.configurationDigest;
  value.identity.configurationDigest = canonicalHash(value);
  return value;
}

test("configuration envelope uses the shared JCS vector before applying local defaults", () => {
  assert.deepEqual(decodeConfiguration(fixture, fixture.scope, fixture.turnId, "pi"), fixture.configuration);
  for (const harness of ["pi", "pi-durable", "codex"]) {
    const value = structuredClone(fixture);
    value.identity.harnessType = harness;
    value.configuration.system = "中文 🧠";
    value.configuration.model.parameters = { temperature: 0.2, maxTokens: 512 };
    seal(value);
    assert.equal(decodeConfiguration(value, fixture.scope, fixture.turnId, harness).system, "中文 🧠");
  }
});

test("modified, missing and misrouted configurations fail before native construction", () => {
  const mutations = [
    (v) => { v.configuration.system = "private prompt must not appear in errors"; },
    (v) => { v.identity.agentVersion++; },
    (v) => { v.turnId = "other-turn"; seal(v); },
    (v) => { v.scope.sessionId = "other-session"; seal(v); },
    (v) => { v.configuration.toolConfiguration.scope.sessionId = "other-session"; seal(v); },
    (v) => { v.identity.harnessType = "codex"; seal(v); },
    (v) => { v.identity.formatVersion = "unknown"; seal(v); },
    (v) => { v.identity.agentVersion = 0; seal(v); },
    (v) => { v.identity.agentId = " "; seal(v); },
    (v) => { delete v.identity; },
  ];
  for (const mutate of mutations) {
    const value = structuredClone(fixture);
    mutate(value);
    assert.throws(() => decodeConfiguration(value, fixture.scope, fixture.turnId, "pi"), (error) =>
      error instanceof TurnRejected && error.message === "Invalid effective configuration identity");
  }
});
