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

export function hasCapability(capabilities: string[] | undefined, want: string): boolean {
  return (capabilities || []).includes(want);
}

export function canCompress(contractLevel: number, capabilities?: string[]): boolean {
  return contractLevel >= 3 && hasCapability(capabilities, 'session-command');
}

/** Terminate uses the same session-command capability as compress. */
export function canTerminate(contractLevel: number, capabilities?: string[]): boolean {
  return canCompress(contractLevel, capabilities);
}

export function canAbort(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'session-abort');
}

export function canQueryContext(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'context-query');
}

/**
 * Live data-plane message-query capability. Operate conversation history no
 * longer pre-gates on this — control-plane transcript is tried first; this
 * flag only describes whether live fallback can succeed.
 */
export function canQueryMessages(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'message-query');
}

export function canQueryTasks(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'task-query');
}

export function canQuerySubagentTasks(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'subagent-task-query');
}

export function canPlanMode(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'plan-mode');
}

export function canQuerySubagentInventory(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'subagent-inventory');
}
