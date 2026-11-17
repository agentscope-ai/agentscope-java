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

/**
 * Outcome of accepting a message into a thread queue.
 *
 * @param status acceptance status
 * @param messageId the message identifier
 * @param position one-based queue position, or {@code -1} when the message was not queued
 * @param state the current state of the message when known
 */
public record QueueAcceptance(Status status, String messageId, int position, String state) {

    /** Acceptance status. */
    public enum Status {
        /** Newly queued. */
        ACCEPTED,
        /** Already known with identical content; not queued twice. */
        DUPLICATE,
        /** Same identifier with different content; rejected. */
        CONFLICT,
        /** Queue is full; rejected. */
        FULL
    }

    /**
     * Creates an acceptance for a newly queued message.
     *
     * @param messageId the message identifier
     * @param position the one-based queue position
     * @return the acceptance
     */
    public static QueueAcceptance accepted(String messageId, int position) {
        return new QueueAcceptance(Status.ACCEPTED, messageId, position, "QUEUED");
    }

    /**
     * Creates an acceptance for an already known message.
     *
     * @param messageId the message identifier
     * @param position the one-based queue position, or {@code -1} when already consumed
     * @param state the known state
     * @return the acceptance
     */
    public static QueueAcceptance duplicate(String messageId, int position, String state) {
        return new QueueAcceptance(Status.DUPLICATE, messageId, position, state);
    }

    /**
     * Creates a rejection for an identifier reused with different content.
     *
     * @param messageId the message identifier
     * @return the acceptance
     */
    public static QueueAcceptance conflict(String messageId) {
        return new QueueAcceptance(Status.CONFLICT, messageId, -1, "CONFLICT");
    }

    /**
     * Creates a rejection for a full queue.
     *
     * @param messageId the message identifier
     * @return the acceptance
     */
    public static QueueAcceptance full(String messageId) {
        return new QueueAcceptance(Status.FULL, messageId, -1, "FULL");
    }

    /**
     * Returns whether the message is present in the queue after this call.
     *
     * @return {@code true} for accepted or duplicate outcomes
     */
    public boolean accepted() {
        return status == Status.ACCEPTED || status == Status.DUPLICATE;
    }
}
