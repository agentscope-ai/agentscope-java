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
package io.agentscope.demo.live;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TranscriptionBlock;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server event for WebSocket communication.
 *
 * <p>Represents events sent from server to client.
 *
 * @param type event type name (e.g., SESSION_CREATED, TEXT_DELTA, AUDIO_DELTA)
 * @param isLast whether this is the last event in a sequence
 * @param message optional message payload containing text, audio, or metadata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerEvent(String type, boolean isLast, MessagePayload message) {

    /**
     * Create a ServerEvent from a LiveEvent.
     *
     * @param event the LiveEvent to convert
     * @return the converted ServerEvent
     */
    public static ServerEvent from(LiveEvent event) {
        MessagePayload payload = null;
        if (event.message() != null) {
            payload = MessagePayload.from(event.message());
        }
        return new ServerEvent(event.type().name(), event.isLast(), payload);
    }

    /**
     * Create an error ServerEvent.
     *
     * @param errorMessage the error message
     * @return the error ServerEvent
     */
    public static ServerEvent error(String errorMessage) {
        return new ServerEvent(
                "ERROR", true, new MessagePayload(errorMessage, null, null, null, null, null));
    }

    /**
     * Message payload containing text, audio, or metadata.
     *
     * @param text text content
     * @param audio audio data as Base64 encoded string
     * @param audioFormat audio format information
     * @param metadata additional metadata
     * @param toolUse tool use request payload
     * @param toolResult tool execution result payload
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessagePayload(
            String text,
            String audio,
            AudioFormat audioFormat,
            Map<String, String> metadata,
            ToolUsePayload toolUse,
            ToolResultPayload toolResult) {

        /**
         * Create a MessagePayload from a Msg.
         *
         * @param msg the Msg to convert
         * @return the converted MessagePayload
         */
        public static MessagePayload from(io.agentscope.core.message.Msg msg) {
            String text = null;
            String audio = null;
            AudioFormat audioFormat = null;
            ToolUsePayload toolUse = null;
            ToolResultPayload toolResult = null;

            if (msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof TextBlock textBlock) {
                        text = textBlock.getText();
                    } else if (block instanceof ThinkingBlock thinkingBlock) {
                        text = thinkingBlock.getThinking();
                    } else if (block instanceof AudioBlock audioBlock) {
                        if (audioBlock.getSource() instanceof RawSource rawSource) {
                            audio = Base64.getEncoder().encodeToString(rawSource.getData());
                            audioFormat =
                                    new AudioFormat(
                                            rawSource.getSampleRate(),
                                            rawSource.getBitDepth(),
                                            rawSource.getChannels());
                        }
                    } else if (block instanceof TranscriptionBlock transcriptionBlock) {
                        text = transcriptionBlock.getText();
                    } else if (block instanceof ToolUseBlock toolUseBlock) {
                        toolUse =
                                new ToolUsePayload(
                                        toolUseBlock.getId(),
                                        toolUseBlock.getName(),
                                        toolUseBlock.getInput());
                    } else if (block instanceof ToolResultBlock toolResultBlock) {
                        String output = extractToolResultOutput(toolResultBlock);
                        boolean isError = output != null && output.startsWith("Error:");
                        toolResult =
                                new ToolResultPayload(
                                        toolResultBlock.getId(),
                                        toolResultBlock.getName(),
                                        output,
                                        isError);
                    }
                }
            }

            Map<String, String> metadata = null;
            if (msg.getMetadata() != null && !msg.getMetadata().isEmpty()) {
                metadata =
                        msg.getMetadata().entrySet().stream()
                                .filter(e -> e.getValue() != null)
                                .collect(
                                        Collectors.toMap(
                                                Map.Entry::getKey, e -> e.getValue().toString()));
            }

            return new MessagePayload(text, audio, audioFormat, metadata, toolUse, toolResult);
        }

        private static String extractToolResultOutput(ToolResultBlock block) {
            if (block.getOutput() == null || block.getOutput().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (ContentBlock content : block.getOutput()) {
                if (content instanceof TextBlock textBlock) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(textBlock.getText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
    }

    /**
     * Audio format information.
     *
     * @param sampleRate sample rate in Hz
     * @param bitDepth bit depth
     * @param channels number of channels
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AudioFormat(int sampleRate, int bitDepth, int channels) {}

    /**
     * Tool use payload for tool call requests.
     *
     * @param id tool call ID
     * @param name tool name
     * @param input tool input parameters
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolUsePayload(String id, String name, Map<String, Object> input) {}

    /**
     * Tool result payload for tool execution results.
     *
     * @param id tool call ID
     * @param name tool name
     * @param output tool execution output
     * @param isError whether the result is an error
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResultPayload(String id, String name, String output, boolean isError) {}
}
