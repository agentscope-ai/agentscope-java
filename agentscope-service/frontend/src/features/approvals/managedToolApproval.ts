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

export interface ManagedToolApprovalRequest {
  kind: "managed_tool_confirmation" | "runtime_tool_confirmation";
  backendKind?: "managed" | "hosted-runtime" | "external-application";
  schemaVersion: 1;
  sessionId: string;
  sessionRef?: string;
  approvalId?: string;
  agentTaskId: string;
  attemptId: string;
  dispatchGeneration: number;
  turnId: string;
  toolUseId: string;
  toolName: string;
  inputPreview?: unknown;
  inputSha256?: string;
  requestedAt?: string | number;
  expiresAt?: string | number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Returns a typed view only for the fenced Managed AgentTask confirmation contract. Keeping this
 * strict prevents arbitrary Approval request JSON from accidentally producing trusted links.
 */
export function managedToolApprovalRequest(
  value: unknown,
): ManagedToolApprovalRequest | undefined {
  if (
    !isRecord(value) ||
    (value.kind !== "managed_tool_confirmation" && value.kind !== "runtime_tool_confirmation") ||
    value.schemaVersion !== 1
  ) {
    return undefined;
  }
  if (
    value.kind === "runtime_tool_confirmation" &&
    value.backendKind !== "hosted-runtime" &&
    value.backendKind !== "external-application"
  ) {
    return undefined;
  }
  if (
    value.kind === "managed_tool_confirmation" &&
    value.backendKind != null &&
    value.backendKind !== "managed"
  ) {
    return undefined;
  }
  const requiredStrings = [
    "sessionId",
    "agentTaskId",
    "attemptId",
    "turnId",
    "toolUseId",
    "toolName",
  ] as const;
  if (requiredStrings.some((key) => typeof value[key] !== "string" || value[key] === "")) {
    return undefined;
  }
  if (
    typeof value.dispatchGeneration !== "number" ||
    !Number.isSafeInteger(value.dispatchGeneration) ||
    value.dispatchGeneration <= 0
  ) {
    return undefined;
  }
  if (value.sessionRef != null && typeof value.sessionRef !== "string") return undefined;
  if (value.approvalId != null && typeof value.approvalId !== "string") return undefined;
  if (value.inputSha256 != null && typeof value.inputSha256 !== "string") return undefined;
  if (
    value.requestedAt != null &&
    typeof value.requestedAt !== "string" &&
    typeof value.requestedAt !== "number"
  ) {
    return undefined;
  }
  if (
    value.expiresAt != null &&
    typeof value.expiresAt !== "string" &&
    typeof value.expiresAt !== "number"
  ) {
    return undefined;
  }
  return value as unknown as ManagedToolApprovalRequest;
}

/** Parses either the control-plane RFC3339 timestamp or the data-plane epoch-millis form. */
export function managedToolApprovalExpiry(
  request: ManagedToolApprovalRequest | undefined,
): number | undefined {
  const value = request?.expiresAt;
  if (typeof value === "number") {
    return Number.isFinite(value) && value > 0 ? value : undefined;
  }
  if (typeof value !== "string" || value === "") return undefined;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? undefined : parsed;
}
