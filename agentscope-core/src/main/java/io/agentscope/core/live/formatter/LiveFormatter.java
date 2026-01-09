/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.live.formatter;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import java.util.List;

/**
 * Live provider adapter interface with generic message type.
 *
 * <p>Design principles:
 *
 * <ul>
 *   <li>Generic type parameter T represents the wire format (String for JSON, byte[] for binary)
 *   <li>Text-based protocols (DashScope, OpenAI, Gemini) use {@code LiveFormatter<String>}
 *   <li>Binary protocols (Doubao) use {@code LiveFormatter<byte[]>}
 *   <li>No unnecessary conversions - formatters work with their native types
 *   <li>Compile-time type safety ensures correct usage
 * </ul>
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@code DashScopeLiveFormatter extends AbstractTextLiveFormatter} - DashScope (JSON)
 *   <li>{@code OpenAILiveFormatter extends AbstractTextLiveFormatter} - OpenAI (JSON)
 *   <li>{@code GeminiLiveFormatter extends AbstractTextLiveFormatter} - Gemini (JSON)
 *   <li>{@code DoubaoLiveFormatter extends AbstractBinaryLiveFormatter} - Doubao (Binary)
 * </ul>
 *
 * @param <T> The wire format type: String for text protocols, byte[] for binary protocols
 */
public interface LiveFormatter<T> {

    /**
     * Convert Msg to WebSocket message in the protocol's native format.
     *
     * <p>Handles all input types:
     *
     * <ul>
     *   <li>AudioBlock - Audio data
     *   <li>TextBlock - Text input (supported by some providers)
     *   <li>ImageBlock - Image input (supported by some providers)
     *   <li>ControlBlock - Control signals (interrupt, commit, etc.)
     *   <li>ToolResultBlock - Tool call results
     * </ul>
     *
     * <p>Unsupported content types are silently ignored and return null.
     *
     * @param msg Input message
     * @return WebSocket message in native format (String or byte[]), null if unsupported
     */
    T formatInput(Msg msg);

    /**
     * Parse server message from native format to LiveEvent.
     *
     * @param data Server message in native format (String or byte[])
     * @return Parsed LiveEvent
     */
    LiveEvent parseOutput(T data);

    /**
     * Build session configuration message (called automatically on connection).
     *
     * <p>Tool definitions are obtained from Agent's Toolkit and passed as a separate parameter.
     * LiveConfig only contains general session configuration, not tool definitions.
     *
     * @param config Session configuration
     * @param toolSchemas Tool definition list (from Toolkit), can be null
     * @return Configuration message in native format (String or byte[])
     */
    T buildSessionConfig(LiveConfig config, List<ToolSchema> toolSchemas);
}
