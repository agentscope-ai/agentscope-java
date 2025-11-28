/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.message;

/**
 * Constants for well-known message metadata keys.
 *
 * <p>This class defines standard metadata keys used across the framework
 * to ensure consistency and avoid magic strings.
 */
public final class MessageMetadataKeys {

    private MessageMetadataKeys() {
        // Prevent instantiation
    }

    /**
     * Metadata key to bypass history merging in multiagent formatters.
     *
     * <p>Messages with this flag set to {@code true} will be kept as separate messages
     * rather than being merged into conversation history when using multiagent formatters.
     *
     * <p><b>Type:</b> Boolean
     * <p><b>Example:</b>
     * <pre>{@code
     * Map<String, Object> metadata = new HashMap<>();
     * metadata.put(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true);
     * Msg msg = Msg.builder()
     *     .role(MsgRole.USER)
     *     .content(TextBlock.builder().text("Important reminder").build())
     *     .metadata(metadata)
     *     .build();
     * }</pre>
     */
    public static final String BYPASS_MULTIAGENT_HISTORY_MERGE = "_bypass_multiagent_history_merge";

    /**
     * Metadata key to mark structured output reminder messages.
     *
     * <p>This flag is used internally by ReActAgent to identify temporary reminder messages
     * that guide the model to use the generate_response tool for structured output.
     *
     * <p><b>Type:</b> Boolean
     * <p><b>Internal use only</b>
     */
    public static final String STRUCTURED_OUTPUT_REMINDER = "_structured_output_reminder";
}
