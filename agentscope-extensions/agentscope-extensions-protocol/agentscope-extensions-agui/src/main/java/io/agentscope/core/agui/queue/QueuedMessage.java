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

import io.agentscope.core.agui.model.RunAgentInput;

/**
 * A message held by a thread queue for later delivery.
 *
 * <p>The message keeps the {@link RunAgentInput} that a client submits to the regular run endpoint.
 * A queued message carries the body as accepted; a claimed message carries a copy whose run id was
 * minted for the replay. Whether a message is still waiting or was delivered is tracked by the
 * queue ledger, so the message itself is immutable.
 */
public final class QueuedMessage {

    /** Lifecycle state of a queued message, as recorded by the queue ledger. */
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
    private final long acceptedAt;

    private QueuedMessage(String messageId, RunAgentInput input, long acceptedAt) {
        this.messageId = messageId;
        this.threadId = input.getThreadId();
        this.runId = input.getRunId();
        this.input = input;
        this.acceptedAt = acceptedAt;
    }

    /**
     * Creates a queued message.
     *
     * @param messageId stable identifier used for deduplication
     * @param input the run input to replay on claim
     * @param acceptedAt acceptance timestamp in epoch milliseconds
     * @return the queued message
     */
    public static QueuedMessage create(String messageId, RunAgentInput input, long acceptedAt) {
        return new QueuedMessage(messageId, input, acceptedAt);
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
     * Returns the run identifier carried by the input.
     *
     * <p>A claimed message carries the fresh identifier minted for the replay, so that replaying it
     * cannot collide with a run id the server has already seen.
     *
     * @return the run identifier
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the run input to replay.
     *
     * @return the run input
     */
    public RunAgentInput getInput() {
        return input;
    }

    /**
     * Returns the acceptance timestamp.
     *
     * @return acceptance timestamp in epoch milliseconds
     */
    public long getAcceptedAt() {
        return acceptedAt;
    }
}
