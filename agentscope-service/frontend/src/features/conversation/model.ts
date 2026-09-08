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

/**
 * Provider-neutral projection consumed by every conversation UI.
 *
 * Runtime-native events remain the durable source of truth. Messages are the
 * readable projection of that log and must not be used to reconstruct native
 * provider state.
 */
export type ConversationRole = 'user' | 'assistant' | 'system' | 'tool' | 'error';

export interface ConversationContentBlock {
  kind: 'text' | 'tool' | 'data' | 'thinking' | 'model';
  id: string;
  text?: string;
  toolName?: string;
  /** Stable provider call id used to pair tool calls and results. */
  callId?: string;
  /** Framework-reported terminal state such as success, error, denied, or interrupted. */
  toolState?: string;
  result?: string;
  durationMs?: number;
  eventSeq?: number;
  resultSeq?: number;
  data?: unknown;
}

export interface ConversationMessage {
  id: string;
  seq?: number;
  role: ConversationRole;
  blocks: ConversationContentBlock[];
  occurredAt?: string;
  turnIndex?: number;
  state?: 'streaming' | 'complete' | 'error';
  truncated?: boolean;
  originalSize?: number;
  /** Native record retained for inspection, never interpreted by the renderer. */
  raw?: unknown;
}

export type ConversationEventCategory =
  | 'message'
  | 'model'
  | 'tool'
  | 'turn'
  | 'lifecycle'
  | 'error'
  | 'other';

export interface ConversationEvent {
  id: string;
  seq?: number;
  type: string;
  category: ConversationEventCategory;
  occurredAt?: string;
  role?: ConversationRole;
  summary?: string;
  messageId?: string;
  callId?: string;
  turnIndex?: number;
  durationMs?: number;
  tokensIn?: number;
  tokensOut?: number;
  /** Lossless provider/framework payload shown in the event inspector. */
  payload?: unknown;
}

export interface ConversationProjection {
  schemaVersion: 'agentscope.conversation.v1';
  messages: ConversationMessage[];
  events: ConversationEvent[];
  source?: string;
}

export const EMPTY_CONVERSATION: ConversationProjection = {
  schemaVersion: 'agentscope.conversation.v1',
  messages: [],
  events: [],
};
