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
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
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
 * <p>All request-side rewriting is done once by {@link Request}, which is built per request in
 * the model and handed to the formatter. Formatter subclasses (including the compat providers
 * that override {@code applyToolChoice}) therefore see already-encoded names and need no
 * changes. Decoding uses the same per-request map, so no shared state is required and
 * concurrent requests cannot interfere. Encoding is identity for already-compliant names.
 *
 * <p>Decoding is applied to each parsed response, including streaming chunks. OpenAI-compatible
 * APIs send the complete function name in the first tool-call delta; if a gateway splits the
 * name itself across deltas, the accumulated name is assembled downstream by the agent's
 * tool-call accumulator and cannot be restored here. Such a name is left as-is rather than
 * being decoded to a wrong tool.
 */
public final class OpenAIToolNameCodec {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolNameCodec.class);

    private OpenAIToolNameCodec() {}

    /**
     * Returns whether the name already satisfies the OpenAI function name pattern.
     *
     * @param name the tool name (null is invalid)
     * @return true if the name can be sent as-is
     */
    public static boolean isValid(String name) {
        return OpenAINameRules.isValid(name);
    }

    /**
     * Encodes a single tool name for the wire. Names already matching the OpenAI pattern are
     * returned unchanged; any other character is replaced with {@code '_'}. Names exceeding 64
     * characters are truncated and given a deterministic hash suffix.
     *
     * @param name the original tool name
     * @return the encoded name, or null if the input was null
     */
    public static String encode(String name) {
        if (name == null || isValid(name)) {
            return name;
        }
        String encoded = OpenAINameRules.replaceIllegalChars(name);
        if (encoded.length() > OpenAINameRules.MAX_LENGTH) {
            encoded = OpenAINameRules.truncateWithHash(encoded, name);
        }
        return encoded;
    }

    /**
     * Builds the per-request mapping for a tool list. Wire names are made unique across the
     * whole list, including names that were already compliant: without this, {@code task.get}
     * and {@code task_get} would collide on {@code task_get} and the second tool could never be
     * dispatched.
     *
     * @param tools the tool schemas of one request
     * @return the request mapping (never null)
     */
    public static Request forTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return new Request(null, Map.of(), Map.of());
        }
        Map<String, String> originalToWire = new LinkedHashMap<>();
        Map<String, String> wireToOriginal = new LinkedHashMap<>();
        Set<String> usedWireNames = new HashSet<>();
        List<ToolSchema> encodedTools = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            String original = tool.getName();
            String wire = disambiguate(encode(original), usedWireNames, original);
            usedWireNames.add(wire);
            if (!wire.equals(original)) {
                wireToOriginal.put(wire, original);
            }
            originalToWire.putIfAbsent(original, wire);
            encodedTools.add(wire.equals(original) ? tool : renameSchema(tool, wire));
        }
        return new Request(encodedTools, originalToWire, wireToOriginal);
    }

    /**
     * Builds a lookup from encoded (wire) names back to original tool names. Only names that
     * change during encoding are included, so an empty map means no decoding is needed.
     *
     * @param tools the tool schemas of one request
     * @return wire name to original name map (never null)
     */
    public static Map<String, String> buildDecodeMap(List<ToolSchema> tools) {
        return forTools(tools).decodeMap();
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
        return encodeToolNames(messages, OpenAIToolNameCodec::encode);
    }

    /**
     * Restores original tool names on a parsed response. Names not present in the decode map
     * are left untouched, so responses pass through unchanged when no encoding happened.
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

    private static List<Msg> encodeToolNames(List<Msg> messages, UnaryOperator<String> encoder) {
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
                    String encoded = encoder.apply(toolUse.getName());
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

    private static String disambiguate(
            String candidate, Set<String> usedWireNames, String original) {
        if (!usedWireNames.contains(candidate)) {
            return candidate;
        }
        log.warn(
                "Tool name '{}' encodes to '{}', which is already used by another tool in this"
                        + " request; appending a suffix to keep wire names unique",
                original,
                candidate);
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            int prefixLength =
                    Math.min(candidate.length(), OpenAINameRules.MAX_LENGTH - suffix.length());
            String alternative = candidate.substring(0, prefixLength) + suffix;
            if (!usedWireNames.contains(alternative)) {
                return alternative;
            }
        }
    }

    private static ToolSchema renameSchema(ToolSchema schema, String name) {
        return ToolSchema.builder()
                .name(name)
                .description(schema.getDescription())
                .parameters(schema.getParameters())
                .outputSchema(schema.getOutputSchema())
                .strict(schema.getStrict())
                .deferLoading(schema.getDeferLoading())
                .toolSearch(schema.isToolSearch())
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

    /** Immutable per-request tool name mapping built from one request's tool list. */
    public static final class Request {

        private final List<ToolSchema> tools;
        private final Map<String, String> originalToWire;
        private final Map<String, String> wireToOriginal;

        private Request(
                List<ToolSchema> tools,
                Map<String, String> originalToWire,
                Map<String, String> wireToOriginal) {
            this.tools = tools;
            this.originalToWire = originalToWire;
            this.wireToOriginal = wireToOriginal;
        }

        /** Whether the request carries tools. */
        public boolean hasTools() {
            return tools != null;
        }

        /** Tool schemas with wire names, ready for the formatter. */
        public List<ToolSchema> tools() {
            return tools;
        }

        /** Wire name for an original name, falling back to the stateless encoder. */
        public String encodeName(String original) {
            String wire = originalToWire.get(original);
            return wire != null ? wire : encode(original);
        }

        /** Encodes a {@link ToolChoice.Specific} name; other choices are returned unchanged. */
        public ToolChoice toolChoice(ToolChoice choice) {
            if (choice instanceof ToolChoice.Specific specific) {
                String wire = encodeName(specific.toolName());
                return wire.equals(specific.toolName()) ? choice : new ToolChoice.Specific(wire);
            }
            return choice;
        }

        /** Encodes tool call names in history messages using this request's mapping. */
        public List<Msg> encodeHistory(List<Msg> messages) {
            return encodeToolNames(messages, this::encodeName);
        }

        /** Restores original tool names on a parsed response. */
        public ChatResponse decode(ChatResponse response) {
            return decodeToolNames(response, wireToOriginal);
        }

        /** Wire name to original name map (empty when no encoding was needed). */
        public Map<String, String> decodeMap() {
            return wireToOriginal;
        }
    }
}
