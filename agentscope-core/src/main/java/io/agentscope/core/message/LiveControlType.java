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

/**
 * Control signal types for Live sessions.
 *
 * <p>These control types are used with {@link ControlBlock} to send
 * control commands during real-time conversations.
 *
 * <p>Provider support:
 * <ul>
 *   <li>COMMIT - DashScope, OpenAI</li>
 *   <li>INTERRUPT - DashScope, OpenAI, Gemini</li>
 *   <li>CLEAR - DashScope, OpenAI</li>
 *   <li>CREATE_RESPONSE - DashScope, OpenAI (when VAD disabled)</li>
 * </ul>
 */
public enum LiveControlType {

    /**
     * Commit audio buffer for VAD processing.
     *
     * <p>Signals the server to process the accumulated audio buffer.
     * Used when manual VAD control is needed.
     *
     * <p>Provider mapping:
     * <ul>
     *   <li>DashScope: input_audio_buffer.commit</li>
     *   <li>OpenAI: input_audio_buffer.commit</li>
     * </ul>
     */
    COMMIT,

    /**
     * Interrupt current model response.
     *
     * <p>Stops the model's current audio/text generation immediately.
     * Useful for implementing barge-in functionality.
     *
     * <p>Provider mapping:
     * <ul>
     *   <li>DashScope: response.cancel</li>
     *   <li>OpenAI: response.cancel</li>
     *   <li>Gemini: clientContent with turnComplete=true</li>
     * </ul>
     */
    INTERRUPT,

    /**
     * Clear input audio buffer.
     *
     * <p>Discards all accumulated audio data in the input buffer.
     *
     * <p>Provider mapping:
     * <ul>
     *   <li>DashScope: input_audio_buffer.clear</li>
     *   <li>OpenAI: input_audio_buffer.clear</li>
     * </ul>
     */
    CLEAR,

    /**
     * Manually trigger model response.
     *
     * <p>Used when VAD is disabled to manually signal that the user
     * has finished speaking and the model should respond.
     *
     * <p>Provider mapping:
     * <ul>
     *   <li>DashScope: response.create</li>
     *   <li>OpenAI: response.create</li>
     * </ul>
     */
    CREATE_RESPONSE
}
