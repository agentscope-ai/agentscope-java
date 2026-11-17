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
package io.agentscope.core.agui.queue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;

/**
 * A user message accepted while the target AG-UI thread was busy.
 *
 * <p>The message keeps the original {@link RunAgentInput} so that a client can claim it later and
 * re-submit the very same payload to the regular run endpoint.
 */
public final class QueuedMessage {

    /** Lifecycle state of a queued message. */
    public enum State {
        /** Waiting in the queue. */
        QUEUED,
        /** Claimed by a client and removed from the queue. */
        CLAIMED
    }

    private final String messageId;
    private final String threadId;
    private final String runId;
    private final RunAgentInput input;
    private final AguiMessage lastUserMessage;
    private final long acceptedAt;
    private volatile State state = State.QUEUED;

    private QueuedMessage(
            String messageId, RunAgentInput input, AguiMessage lastUserMessage, long acceptedAt) {
        this.messageId = messageId;
        this.threadId = input.getThreadId();
        this.runId = input.getRunId();
        this.input = input;
        this.lastUserMessage = lastUserMessage;
        this.acceptedAt = acceptedAt;
    }

    /**
     * Creates a queued message.
     *
     * @param messageId stable identifier used for deduplication
     * @param input the original run input to replay on claim
     * @param lastUserMessage the trailing user message, used as the content fingerprint
     * @param acceptedAt acceptance timestamp in epoch milliseconds
     * @return the queued message
     */
    public static QueuedMessage create(
            String messageId, RunAgentInput input, AguiMessage lastUserMessage, long acceptedAt) {
        return new QueuedMessage(messageId, input, lastUserMessage, acceptedAt);
    }

    /**
     * Marks this message as claimed.
     *
     * <p>Claiming removes the message from the queue, so this state is terminal.
     */
    public void markClaimed() {
        this.state = State.CLAIMED;
    }

    /**
     * Returns the message identifier.
     *
     * @return the message identifier
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Returns the target thread identifier.
     *
     * @return the thread identifier
     */
    public String getThreadId() {
        return threadId;
    }

    /**
     * Returns the run identifier of the original request.
     *
     * @return the run identifier
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the original run input.
     *
     * @return the original run input
     */
    public RunAgentInput getInput() {
        return input;
    }

    /**
     * Returns the trailing user message used as the content fingerprint.
     *
     * @return the trailing user message, may be {@code null}
     */
    public AguiMessage getLastUserMessage() {
        return lastUserMessage;
    }

    /**
     * Returns the acceptance timestamp.
     *
     * @return acceptance timestamp in epoch milliseconds
     */
    public long getAcceptedAt() {
        return acceptedAt;
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return the current state
     */
    public State getState() {
        return state;
    }
}
