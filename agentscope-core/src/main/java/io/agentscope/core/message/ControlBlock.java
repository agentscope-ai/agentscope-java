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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Control signal content block for Live sessions.
 *
 * <p>Used to send control commands during real-time conversations,
 * such as interrupting the model, committing audio buffer, etc.
 *
 * <p>Usage example:
 * <pre>{@code
 * // Interrupt current response
 * Msg interruptMsg = Msg.builder()
 *     .role(MsgRole.CONTROL)
 *     .content(ControlBlock.interrupt())
 *     .build();
 *
 * // Commit audio buffer
 * Msg commitMsg = Msg.builder()
 *     .role(MsgRole.CONTROL)
 *     .content(ControlBlock.commit())
 *     .build();
 *
 * // Manually trigger response (when VAD disabled)
 * Msg createResponseMsg = Msg.builder()
 *     .role(MsgRole.CONTROL)
 *     .content(ControlBlock.createResponse())
 *     .build();
 * }</pre>
 *
 * @see LiveControlType
 * @see MsgRole#CONTROL
 */
public final class ControlBlock extends ContentBlock {

    private final LiveControlType controlType;
    private final Map<String, Object> parameters;

    /**
     * Creates a new control block.
     *
     * @param controlType the type of control signal
     * @param parameters optional parameters for the control signal
     */
    @JsonCreator
    public ControlBlock(
            @JsonProperty("controlType") LiveControlType controlType,
            @JsonProperty("parameters") Map<String, Object> parameters) {
        this.controlType = Objects.requireNonNull(controlType, "controlType cannot be null");
        this.parameters = parameters != null ? Map.copyOf(parameters) : Collections.emptyMap();
    }

    /**
     * Gets the control type.
     *
     * @return the control type
     */
    public LiveControlType getControlType() {
        return controlType;
    }

    /**
     * Gets the optional parameters.
     *
     * @return immutable map of parameters
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * Gets a parameter value by key.
     *
     * @param key the parameter key
     * @param <T> the expected value type
     * @return the parameter value, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a control block with the specified type.
     *
     * @param type the control type
     * @return a new ControlBlock
     */
    public static ControlBlock of(LiveControlType type) {
        return new ControlBlock(type, null);
    }

    /**
     * Creates a control block with the specified type and parameters.
     *
     * @param type the control type
     * @param parameters optional parameters
     * @return a new ControlBlock
     */
    public static ControlBlock of(LiveControlType type, Map<String, Object> parameters) {
        return new ControlBlock(type, parameters);
    }

    /**
     * Creates a COMMIT control block.
     *
     * <p>Signals the server to process the accumulated audio buffer.
     *
     * @return a new ControlBlock with COMMIT type
     */
    public static ControlBlock commit() {
        return of(LiveControlType.COMMIT);
    }

    /**
     * Creates an INTERRUPT control block.
     *
     * <p>Stops the model's current audio/text generation immediately.
     *
     * @return a new ControlBlock with INTERRUPT type
     */
    public static ControlBlock interrupt() {
        return of(LiveControlType.INTERRUPT);
    }

    /**
     * Creates a CLEAR control block.
     *
     * <p>Discards all accumulated audio data in the input buffer.
     *
     * @return a new ControlBlock with CLEAR type
     */
    public static ControlBlock clear() {
        return of(LiveControlType.CLEAR);
    }

    /**
     * Creates a CREATE_RESPONSE control block.
     *
     * <p>Manually triggers model response when VAD is disabled.
     *
     * @return a new ControlBlock with CREATE_RESPONSE type
     */
    public static ControlBlock createResponse() {
        return of(LiveControlType.CREATE_RESPONSE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ControlBlock that = (ControlBlock) o;
        return controlType == that.controlType && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(controlType, parameters);
    }

    @Override
    public String toString() {
        return "ControlBlock{" + "controlType=" + controlType + ", parameters=" + parameters + '}';
    }
}
