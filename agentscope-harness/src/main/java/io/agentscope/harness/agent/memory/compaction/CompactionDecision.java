/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.event.CompactionStartEvent.TriggerReason;
import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Result of {@link ConversationCompactor#probe(List, CompactionConfig)}.
 *
 * <p>Separating the trigger decision from the summarization work lets callers (notably
 * {@code CompactionMiddleware}) emit {@code COMPACTION_START} only when compaction will
 * actually run, avoiding speculative start events that get cancelled by internal skip paths.
 */
public sealed interface CompactionDecision
        permits CompactionDecision.Proceed, CompactionDecision.Skip {

    /**
     * Compaction should proceed. Carries the non-LLM pruning result and the cutoff already
     * computed, so {@link ConversationCompactor#execute} does not repeat that work.
     *
     * @param prunedMessages  conversation messages after non-LLM truncation and tool-result
     *                        pruning; length may be less than the input
     * @param cutoff          index in {@code prunedMessages} separating prefix (to summarize)
     *                        from tail (to preserve)
     * @param triggerReason   which threshold dimension caused the trigger
     * @param thresholdValue  configured limit for that dimension (tokens or messages)
     * @param estimatedTokens estimated token count of {@code prunedMessages}
     * @param messageCount    size of {@code prunedMessages}
     */
    record Proceed(
            List<Msg> prunedMessages,
            int cutoff,
            TriggerReason triggerReason,
            int thresholdValue,
            int estimatedTokens,
            int messageCount)
            implements CompactionDecision {}

    /** Compaction is not needed; caller should pass through without emitting compaction events. */
    record Skip() implements CompactionDecision {
        public static final Skip INSTANCE = new Skip();
    }
}
