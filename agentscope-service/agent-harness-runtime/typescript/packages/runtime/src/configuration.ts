import {
  canonicalHash,
  TurnRejected,
  type Scope,
  type RunConfiguration,
} from "./index.js";

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error();
  return value as Record<string, unknown>;
}

/** Check the service's sealed envelope before local scheduling defaults or native construction. */
export function decodeConfiguration(
  response: unknown,
  scope: Scope,
  turnId: string,
  harnessType: string,
): RunConfiguration {
  try {
    const value = object(response);
    const identity = object(value.identity);
    const configuration = object(value.configuration);
    if (
      value.protocolVersion !== "0.1.0" ||
      value.turnId !== turnId ||
      canonicalHash(value.scope) !== canonicalHash(scope) ||
      identity.formatVersion !== "managed-effective-agent-v1" ||
      typeof identity.harnessType !== "string" ||
      !["pi", "pi-durable", "codex"].includes(identity.harnessType) ||
      identity.harnessType !== harnessType ||
      typeof identity.agentId !== "string" ||
      !/\S/u.test(identity.agentId) ||
      [...identity.agentId].length > 1024 ||
      Object.keys(value).some((key) =>
        !["protocolVersion", "scope", "turnId", "identity", "configuration"].includes(key),
      ) ||
      Object.keys(identity).some((key) =>
        !["formatVersion", "harnessType", "agentId", "agentVersion",
          "sourceDigest", "effectiveDigest", "configurationDigest"].includes(key),
      ) ||
      typeof identity.agentVersion !== "number" ||
      !Number.isInteger(identity.agentVersion) ||
      identity.agentVersion < 1 || identity.agentVersion > 2147483647 ||
      ![identity.sourceDigest, identity.effectiveDigest,
        identity.configurationDigest].every(
        (hash) => typeof hash === "string" && /^[0-9a-f]{64}$/.test(hash),
      ) ||
      canonicalHash(object(configuration.toolConfiguration).scope) !== canonicalHash(scope)
    ) throw new Error();
    const { configurationDigest, ...provenance } = identity;
    if (canonicalHash({ ...value, identity: provenance }) !== configurationDigest)
      throw new Error();
    return configuration as unknown as RunConfiguration;
  } catch {
    // Do not include prompts, resources, or credentials from the offending payload in diagnostics.
    throw new TurnRejected("Invalid effective configuration identity");
  }
}
