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

package io.agentscope.core.model;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallAttemptRole;
import java.util.function.Consumer;

/**
 * Runtime-only context for emitting attempt lifecycle events from within the
 * model transport pipeline.
 *
 * <p>This object is <b>not serialized</b> and does not participate in JSON
 * round-trips. It is set by {@code ReActAgent} (or any caller that owns the
 * event sink) and read by {@link ModelUtils#applyTimeoutAndRetry} to wire the
 * tracker into the retry pipeline without changing Provider signatures.
 *
 * <p>Usage:
 * <pre>{@code
 * ExecutionConfig cfg = ExecutionConfig.builder()
 *     .maxAttempts(3)
 *     .attemptEventContext(new AttemptEventContext(emitter, replyId, role))
 *     .build();
 * }</pre>
 */
public final class AttemptEventContext {

    private final Consumer<AgentEvent> emitter;
    private final String replyId;
    private final ModelCallAttemptRole role;
    private final boolean hasFallback;

    /**
     * Creates a new attempt event context.
     *
     * @param emitter the event consumer to receive attempt lifecycle events
     * @param replyId the logical call/reply identifier
     * @param role whether this context is for a primary or fallback model call
     */
    public AttemptEventContext(
            Consumer<AgentEvent> emitter, String replyId, ModelCallAttemptRole role) {
        this(emitter, replyId, role, false);
    }

    /**
     * Creates a new attempt event context with explicit fallback availability.
     *
     * @param emitter the event consumer to receive attempt lifecycle events
     * @param replyId the logical call/reply identifier
     * @param role whether this context is for a primary or fallback model call
     * @param hasFallback whether a fallback model is configured (only meaningful for PRIMARY role)
     */
    public AttemptEventContext(
            Consumer<AgentEvent> emitter,
            String replyId,
            ModelCallAttemptRole role,
            boolean hasFallback) {
        this.emitter = emitter;
        this.replyId = replyId;
        this.role = role;
        this.hasFallback = hasFallback;
    }

    /** Returns the event emitter, or null if not set. */
    public Consumer<AgentEvent> getEmitter() {
        return emitter;
    }

    /** Returns the reply identifier, or null if not set. */
    public String getReplyId() {
        return replyId;
    }

    /** Returns the attempt role (primary or fallback). */
    public ModelCallAttemptRole getRole() {
        return role;
    }

    /** Returns true if a fallback model is configured for this call. */
    public boolean hasFallback() {
        return hasFallback;
    }

    /** Returns true if the context has all required fields populated. */
    public boolean isComplete() {
        return emitter != null && replyId != null && role != null;
    }
}
