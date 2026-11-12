/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { AgentCheckpointCommand } from '../../api/agentSessions';

export interface CheckpointRetry {
  checkpointId: string; target?: string; key: string; receipt?: AgentCheckpointCommand;
}
const storageKey = (session: string) => `managed-agents.checkpoint-retry.v2.${session}`;
export function readCheckpointRetry(session: string): { pending?: CheckpointRetry; error?: string } {
  try {
    const value = sessionStorage.getItem(storageKey(session));
    if (!value) return {};
    if (value.length > 8192) throw new Error();
    const parsed = JSON.parse(value) as CheckpointRetry;
    if (typeof parsed.checkpointId !== 'string' || !parsed.checkpointId.trim() || parsed.checkpointId.length > 256
        || typeof parsed.key !== 'string' || !parsed.key || parsed.key.length > 256
        || parsed.target !== undefined && (typeof parsed.target !== 'string' || !parsed.target || parsed.target.length > 256)
        || parsed.receipt && (typeof parsed.receipt.command_id !== 'string' || !parsed.receipt.command_id
          || parsed.receipt.operation_id !== parsed.receipt.command_id || parsed.receipt.source_session_id !== session
          || typeof parsed.receipt.command_status !== 'string' || parsed.receipt.session_id !== (parsed.target ?? session)))
      throw new Error();
    return { pending: parsed };
  } catch { return { error: 'The pending recovery request could not be read. Check its outcome before starting another recovery.' }; }
}
export function saveCheckpointRetry(session: string, value: CheckpointRetry, existingOnly = false) {
  const encoded = JSON.stringify(value);
  if (new TextEncoder().encode(encoded).length > 8192) throw new Error('Recovery request is too large to retain in this tab.');
  try {
    const current = readCheckpointRetry(session);
    if (current.error) throw new Error();
    if (current.pending?.key !== value.key) {
      if (existingOnly) return;
      if (current.pending) throw new Error();
    }
    sessionStorage.setItem(storageKey(session), encoded);
  }
  catch { throw new Error('This tab cannot retain the recovery request.'); }
}
export function clearCheckpointRetry(session: string, key: string) {
  if (readCheckpointRetry(session).pending?.key === key) sessionStorage.removeItem(storageKey(session));
}
