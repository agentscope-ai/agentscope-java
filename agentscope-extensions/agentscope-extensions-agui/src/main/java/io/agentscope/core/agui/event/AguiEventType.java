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
package io.agentscope.core.agui.event;

/**
 * Enumeration of all AG-UI protocol event types.
 */
public enum AguiEventType {
    /**
     * Indicates that an agent run has started.
     */
    RUN_STARTED,

    /**
     * Indicates that an agent run has finished.
     */
    RUN_FINISHED,

    /**
     * Indicates the start of a text message.
     */
    TEXT_MESSAGE_START,

    /**
     * Contains text content for a message.
     */
    TEXT_MESSAGE_CONTENT,

    /**
     * Indicates the end of a text message.
     */
    TEXT_MESSAGE_END,

    /**
     * Indicates the start of a tool call.
     */
    TOOL_CALL_START,

    /**
     * Contains arguments for a tool call.
     */
    TOOL_CALL_ARGS,

    /**
     * Indicates the end of a tool call.
     */
    TOOL_CALL_END,

    /**
     * Contains the result of a tool call.
     */
    TOOL_CALL_RESULT,

    /**
     * Contains a snapshot of the current state.
     */
    STATE_SNAPSHOT,

    /**
     * Contains a delta update to the state.
     */
    STATE_DELTA,

    /**
     * A raw event with custom data.
     */
    RAW
}
