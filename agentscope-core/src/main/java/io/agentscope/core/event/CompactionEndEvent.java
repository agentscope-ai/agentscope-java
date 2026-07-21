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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Emitted when context compaction completes.
 *
 * <p>Always paired with a preceding {@link CompactionStartEvent}. The {@link #getOutcome()}
 * field distinguishes successful summarization from a failure that fell back to the raw
 * conversation.
 *
 * <p>{@link #getBeforeTokens()} and {@link #getAfterTokens()} give the observed token counts;
 * {@code afterTokens} may exceed {@code beforeTokens} in rare cases (for example when a large
 * preserved tail of tool results outweighs the removed prefix). Consumers should treat these
 * as observations, not as a guaranteed reduction.
 */
public class CompactionEndEvent extends AgentEvent {

    /** Outcome of the compaction attempt. */
    public enum Outcome {
        /** Prefix was summarized and the working conversation was rebuilt. */
        COMPACTED,
        /** Compaction was attempted but failed; the caller fell back to the raw conversation. */
        FAILED
    }

    private final Outcome outcome;
    private final int originalMessageCount;
    private final int compactedMessageCount;
    private final int beforeTokens;
    private final int afterTokens;

    @JsonCreator
    public CompactionEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("originalMessageCount") int originalMessageCount,
            @JsonProperty("compactedMessageCount") int compactedMessageCount,
            @JsonProperty("beforeTokens") int beforeTokens,
            @JsonProperty("afterTokens") int afterTokens) {
        super(id, createdAt);
        this.outcome = outcome != null ? outcome : Outcome.COMPACTED;
        this.originalMessageCount = originalMessageCount;
        this.compactedMessageCount = compactedMessageCount;
        this.beforeTokens = beforeTokens;
        this.afterTokens = afterTokens;
    }

    /**
     * Constructs a compaction-end event.
     *
     * @param outcome                whether the attempt succeeded or fell back
     * @param originalMessageCount   number of conversation messages before compaction
     * @param compactedMessageCount  number of conversation messages after compaction
     *                               (equals {@code originalMessageCount} when {@code outcome ==
     *                               FAILED})
     * @param beforeTokens           estimated token count before compaction
     * @param afterTokens            estimated token count after compaction (equals
     *                               {@code beforeTokens} when {@code outcome == FAILED})
     */
    public CompactionEndEvent(
            Outcome outcome,
            int originalMessageCount,
            int compactedMessageCount,
            int beforeTokens,
            int afterTokens) {
        this.outcome = outcome != null ? outcome : Outcome.COMPACTED;
        this.originalMessageCount = originalMessageCount;
        this.compactedMessageCount = compactedMessageCount;
        this.beforeTokens = beforeTokens;
        this.afterTokens = afterTokens;
    }

    /**
     * Legacy three-arg constructor kept for source compatibility. Assumes a successful outcome
     * and derives {@code beforeTokens} / {@code afterTokens} from
     * {@code estimatedTokensSaved} (treating {@code before} as unknown-but-nonnegative).
     *
     * @deprecated use {@link #CompactionEndEvent(Outcome, int, int, int, int)}
     */
    @Deprecated
    public CompactionEndEvent(
            int originalMessageCount, int compactedMessageCount, int estimatedTokensSaved) {
        this(
                Outcome.COMPACTED,
                originalMessageCount,
                compactedMessageCount,
                Math.max(0, estimatedTokensSaved),
                Math.max(0, estimatedTokensSaved) - estimatedTokensSaved);
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.COMPACTION_END;
    }

    /** Whether the compaction attempt succeeded or fell back to the raw conversation. */
    public Outcome getOutcome() {
        return outcome;
    }

    /** Number of conversation messages before compaction. */
    public int getOriginalMessageCount() {
        return originalMessageCount;
    }

    /**
     * Number of conversation messages after compaction. Equals {@link #getOriginalMessageCount()}
     * when {@link #getOutcome()} is {@link Outcome#FAILED}.
     */
    public int getCompactedMessageCount() {
        return compactedMessageCount;
    }

    /** Estimated token count before compaction ran. */
    public int getBeforeTokens() {
        return beforeTokens;
    }

    /**
     * Estimated token count after compaction. Equals {@link #getBeforeTokens()} when
     * {@link #getOutcome()} is {@link Outcome#FAILED}. May exceed {@code beforeTokens} in rare
     * cases where the preserved tail is large.
     */
    public int getAfterTokens() {
        return afterTokens;
    }

    /**
     * Legacy accessor returning {@code beforeTokens - afterTokens}. May be negative when the
     * preserved tail outweighs the removed prefix.
     *
     * @deprecated use {@link #getBeforeTokens()} and {@link #getAfterTokens()} directly
     */
    @Deprecated
    public int getEstimatedTokensSaved() {
        return beforeTokens - afterTokens;
    }
}
