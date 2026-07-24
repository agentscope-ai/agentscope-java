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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents one official AG-UI resume response for a previous interrupt.
 *
 * <p>Resume entries are sent on the next {@link RunAgentInput} for the same thread and are
 * correlated with the interrupted run by {@code interruptId}.
 *
 * <p><b>Important distinction:</b> A user response that explicitly denies the request
 * (e.g., {@code { "approved": false }}) is still considered {@code resolved},
 * <b>not</b> {@code cancelled}. The {@code cancelled} status is reserved for cases
 * where the user provides no meaningful input and the {@code payload} is omitted.
 */
public class AguiResume {

    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_CANCELLED = "cancelled";

    private final String interruptId;
    private final String status;
    private final Object payload;

    /**
     * Creates a new AG-UI resume entry.
     *
     * @param interruptId The interrupt ID from {@code RunFinished.outcome.interrupts[]}
     * @param status The resume status, usually {@code resolved} or {@code cancelled}
     * @param payload Optional response payload for resolved interrupts
     */
    @JsonCreator
    public AguiResume(
            @JsonProperty("interruptId") String interruptId,
            @JsonProperty("status") String status,
            @JsonProperty("payload") Object payload) {
        this.interruptId = Objects.requireNonNull(interruptId, "interruptId cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.payload = payload;
    }

    /**
     * Get the interrupt ID this resume entry answers.
     *
     * @return The interrupt ID
     */
    public String getInterruptId() {
        return interruptId;
    }

    /**
     * Get the resume status.
     *
     * @return The status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Get the resume payload.
     *
     * @return The payload, or null if not supplied
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * Check whether this resume entry resolved the interrupt.
     *
     * @return true if status is {@code resolved}
     */
    public boolean isResolved() {
        return STATUS_RESOLVED.equals(status);
    }

    /**
     * Check whether this resume entry cancelled the interrupt.
     *
     * @return true if status is {@code cancelled}
     */
    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    @Override
    public String toString() {
        return "AguiResume{interruptId='"
                + interruptId
                + "', status='"
                + status
                + "', payload="
                + payload
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiResume that = (AguiResume) o;
        return Objects.equals(interruptId, that.interruptId)
                && Objects.equals(status, that.status)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(interruptId, status, payload);
    }
}
