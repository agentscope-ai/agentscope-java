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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional codec for tool names that violate the OpenAI function-calling name pattern
 * {@code ^[a-zA-Z0-9_-]{1,64}$}.
 *
 * <p>Some tool sources (e.g. MCP servers) register names containing dots or other characters
 * that strictly-validating OpenAI-compatible gateways reject with a 400 error. This codec
 * encodes such names before they are sent on the wire (tool definitions, tool_choice, and
 * tool calls in history messages) and restores the original names on parsed responses so
 * that tool dispatch keeps working against the original registration.
 *
 * <p>Encoding is deterministic and identity for already-valid names. Decoding uses a
 * per-request map built from the request's tool list, so no shared state is required and
 * concurrent requests cannot interfere.
 */
public final class OpenAIToolNameCodec {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolNameCodec.class);

    private static final int MAX_NAME_LENGTH = 64;

    private OpenAIToolNameCodec() {}

    /**
     * Returns whether the name already satisfies the OpenAI function name pattern.
     *
     * @param name the tool name (null is invalid)
     * @return true if the name can be sent as-is
     */
    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isValidChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Encodes a tool name for the wire. Names already matching the OpenAI pattern are returned
     * unchanged; any other character is replaced with {@code '_'}. Names exceeding 64
     * characters are truncated and given a deterministic hash suffix.
     *
     * @param name the original tool name
     * @return the encoded name, or null if the input was null
     */
    public static String encode(String name) {
        if (name == null || isValid(name)) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(isValidChar(c) ? c : '_');
        }
        String encoded = sb.toString();
        if (encoded.length() > MAX_NAME_LENGTH) {
            String suffix = String.format("%08x", name.hashCode());
            encoded = encoded.substring(0, MAX_NAME_LENGTH - suffix.length() - 1) + "_" + suffix;
        }
        return encoded;
    }

    /**
     * Builds a lookup from encoded (wire) names back to original tool names. Only names that
     * change during encoding are included, so an empty map means no decoding is needed.
     *
     * @param tools the tool schemas of one request
     * @return wire name to original name map (never null)
     */
    public static Map<String, String> buildDecodeMap(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (ToolSchema tool : tools) {
            String original = tool.getName();
            String encoded = encode(original);
            if (encoded != null && !encoded.equals(original)) {
                String previous = map.putIfAbsent(encoded, original);
                if (previous != null && !previous.equals(original)) {
                    log.warn(
                            "Tool name encoding collision: '{}' and '{}' both encode to '{}';"
                                    + " returned calls will be decoded to '{}'",
                            previous,
                            original,
                            encoded,
                            previous);
                }
            }
        }
        return map;
    }

    /**
     * Returns a copy of the message list where every {@link ToolUseBlock} name is encoded for
     * the wire. The input list and its messages are not modified; if no name needs encoding
     * the same list instance is returned.
     *
     * @param messages history messages about to be formatted
     * @return messages with encoded tool call names
     */
    public static List<Msg> encodeToolNames(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        List<Msg> result = messages;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            List<ContentBlock> content = msg.getContent();
            List<ContentBlock> updated = null;
            for (int j = 0; j < content.size(); j++) {
                if (content.get(j) instanceof ToolUseBlock toolUse) {
                    String encoded = encode(toolUse.getName());
                    if (encoded != null && !encoded.equals(toolUse.getName())) {
                        if (updated == null) {
                            updated = new ArrayList<>(content);
                        }
                        updated.set(j, rename(toolUse, encoded));
                    }
                }
            }
            if (updated != null) {
                if (result == messages) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, msg.withContent(updated));
            }
        }
        return result;
    }

    /**
     * Restores original tool names on a parsed response using a decode map built by {@link
     * #buildDecodeMap(List)}. Names not present in the map are left untouched, so responses
     * pass through unchanged when no encoding happened.
     *
     * @param response the parsed chat response (may be null)
     * @param decodeMap wire name to original name map
     * @return the response with restored tool names
     */
    public static ChatResponse decodeToolNames(
            ChatResponse response, Map<String, String> decodeMap) {
        if (response == null || decodeMap == null || decodeMap.isEmpty()) {
            return response;
        }
        List<ContentBlock> content = response.getContent();
        if (content == null || content.isEmpty()) {
            return response;
        }
        List<ContentBlock> updated = null;
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof ToolUseBlock toolUse) {
                String original = decodeMap.get(toolUse.getName());
                if (original != null && !original.equals(toolUse.getName())) {
                    if (updated == null) {
                        updated = new ArrayList<>(content);
                    }
                    updated.set(i, rename(toolUse, original));
                }
            }
        }
        if (updated == null) {
            return response;
        }
        return ChatResponse.builder()
                .id(response.getId())
                .content(updated)
                .usage(response.getUsage())
                .metadata(response.getMetadata())
                .finishReason(response.getFinishReason())
                .build();
    }

    private static ToolUseBlock rename(ToolUseBlock block, String name) {
        return ToolUseBlock.builder()
                .id(block.getId())
                .name(name)
                .input(block.getInput())
                .content(block.getContent())
                .metadata(block.getMetadata())
                .state(block.getState())
                .build();
    }

    private static boolean isValidChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }
}
