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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Emitted when context compaction is about to run.
 *
 * <p>This event is guaranteed to be followed by a matching {@link CompactionEndEvent}. It is
 * only emitted when compaction work will actually execute — i.e., after cheap non-LLM pruning
 * has been evaluated and a safe cutoff has been found. See
 * {@code CompactionMiddleware.onReasoning}.
 *
 * <p>Two threshold dimensions can trigger compaction, described by {@link #getTriggerReason()}:
 * <ul>
 *   <li>{@link TriggerReason#TOKEN_THRESHOLD} — estimated token count reached
 *       {@code triggerTokens}. {@link #getThresholdValue()} is the token limit.</li>
 *   <li>{@link TriggerReason#MESSAGE_THRESHOLD} — conversation length reached
 *       {@code triggerMessages}. {@link #getThresholdValue()} is the message-count limit.</li>
 * </ul>
 */
public class CompactionStartEvent extends AgentEvent {

    /** Which threshold dimension fired the compaction trigger. */
    public enum TriggerReason {
        TOKEN_THRESHOLD,
        MESSAGE_THRESHOLD
    }

    private final TriggerReason triggerReason;
    private final int thresholdValue;
    private final int estimatedTokens;
    private final int messageCount;

    @JsonCreator
    public CompactionStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("triggerReason") TriggerReason triggerReason,
            @JsonProperty("thresholdValue") @JsonAlias("triggerThreshold") int thresholdValue,
            @JsonProperty("estimatedTokens") int estimatedTokens,
            @JsonProperty("messageCount") int messageCount) {
        super(id, createdAt);
        this.triggerReason = triggerReason != null ? triggerReason : TriggerReason.TOKEN_THRESHOLD;
        this.thresholdValue = thresholdValue;
        this.estimatedTokens = estimatedTokens;
        this.messageCount = messageCount;
    }

    /**
     * Constructs a compaction-start event with full trigger context.
     *
     * @param triggerReason   which dimension crossed its threshold
     * @param thresholdValue  the configured limit for that dimension (tokens or messages)
     * @param estimatedTokens estimated token count of the conversation at trigger time
     * @param messageCount    number of conversation messages at trigger time
     */
    public CompactionStartEvent(
            TriggerReason triggerReason,
            int thresholdValue,
            int estimatedTokens,
            int messageCount) {
        this.triggerReason = triggerReason != null ? triggerReason : TriggerReason.TOKEN_THRESHOLD;
        this.thresholdValue = thresholdValue;
        this.estimatedTokens = estimatedTokens;
        this.messageCount = messageCount;
    }

    /**
     * Legacy two-arg constructor kept for source compatibility. Assumes a token-threshold trigger
     * and leaves {@code messageCount} at 0. New code should use the four-arg form.
     *
     * @deprecated use {@link #CompactionStartEvent(TriggerReason, int, int, int)}
     */
    @Deprecated
    public CompactionStartEvent(int estimatedTokens, int triggerThreshold) {
        this(TriggerReason.TOKEN_THRESHOLD, triggerThreshold, estimatedTokens, 0);
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.COMPACTION_START;
    }

    /** Which threshold dimension caused compaction to fire. */
    public TriggerReason getTriggerReason() {
        return triggerReason;
    }

    /**
     * The configured limit for the fired dimension: a token count when
     * {@link #getTriggerReason()} is {@link TriggerReason#TOKEN_THRESHOLD}, a message count
     * when it is {@link TriggerReason#MESSAGE_THRESHOLD}.
     */
    public int getThresholdValue() {
        return thresholdValue;
    }

    /** Estimated token count of the conversation at trigger time. */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /** Number of conversation messages (non-SYSTEM) at trigger time. */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * Legacy accessor returning {@link #getThresholdValue()}.
     *
     * @deprecated use {@link #getThresholdValue()} together with {@link #getTriggerReason()}
     */
    @Deprecated
    public int getTriggerThreshold() {
        return thresholdValue;
    }
}
