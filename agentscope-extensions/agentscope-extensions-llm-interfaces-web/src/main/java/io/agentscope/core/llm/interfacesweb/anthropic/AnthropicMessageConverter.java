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
package io.agentscope.core.llm.interfacesweb.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.llm.interfacesweb.common.ProtocolException;
import io.agentscope.core.llm.interfacesweb.common.ProtocolJsonUtils;
import io.agentscope.core.llm.interfacesweb.common.ProtocolMessageUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts Anthropic Messages request payloads into AgentScope messages. */
public class AnthropicMessageConverter {

    public List<Msg> convert(AnthropicMessagesRequest request) {
        List<Msg> messages = new ArrayList<>();
        String system = systemText(ProtocolJsonUtils.toJsonNode(request.getSystem()));
        if (system != null && !system.isBlank()) {
            messages.add(ProtocolMessageUtils.textMessage(MsgRole.SYSTEM, system));
        }
        if (request.getMessages() != null) {
            for (AnthropicMessage message : request.getMessages()) {
                messages.add(convertMessage(message));
            }
        }
        if (messages.isEmpty()) {
            throw new ProtocolException(
                    "invalid_request_error", "At least one message is required");
        }
        return messages;
    }

    private Msg convertMessage(AnthropicMessage message) {
        MsgRole role = roleToMsgRole(message.getRole());
        return ProtocolMessageUtils.message(
                role, convertContent(ProtocolJsonUtils.toJsonNode(message.getContent())));
    }

    private List<ContentBlock> convertContent(JsonNode content) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (content == null || content.isNull()) {
            return List.of(TextBlock.builder().text("").build());
        }
        if (content.isTextual()) {
            return List.of(TextBlock.builder().text(content.asText()).build());
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                ContentBlock block = convertPart(part);
                if (block != null) {
                    blocks.add(block);
                }
            }
        } else {
            ContentBlock block = convertPart(content);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks.isEmpty() ? List.of(TextBlock.builder().text("").build()) : blocks;
    }

    private ContentBlock convertPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return null;
        }
        if (part.isTextual()) {
            return TextBlock.builder().text(part.asText()).build();
        }
        String type = ProtocolJsonUtils.textValue(part, "type");
        if ("text".equals(type)) {
            return TextBlock.builder().text(ProtocolJsonUtils.textValue(part, "text")).build();
        }
        if ("image".equals(type)) {
            return imagePart(part.get("source"));
        }
        if ("tool_use".equals(type)) {
            JsonNode input = part.get("input");
            return ToolUseBlock.builder()
                    .id(ProtocolJsonUtils.textValue(part, "id"))
                    .name(ProtocolJsonUtils.textValue(part, "name"))
                    .input(input != null ? ProtocolJsonUtils.toMap(input) : Map.of())
                    .content(input != null ? ProtocolJsonUtils.toJson(input) : "{}")
                    .build();
        }
        if ("tool_result".equals(type)) {
            return ToolResultBlock.builder()
                    .id(ProtocolJsonUtils.textValue(part, "tool_use_id"))
                    .output(TextBlock.builder().text(extractText(part.get("content"))).build())
                    .build();
        }
        return TextBlock.builder().text(extractText(part)).build();
    }

    private ContentBlock imagePart(JsonNode source) {
        if (source == null || source.isNull()) {
            return TextBlock.builder().text("[Unsupported image]").build();
        }
        String sourceType = ProtocolJsonUtils.textValue(source, "type");
        if ("url".equals(sourceType)) {
            return ImageBlock.builder()
                    .source(
                            URLSource.builder()
                                    .url(ProtocolJsonUtils.textValue(source, "url"))
                                    .build())
                    .build();
        }
        if ("base64".equals(sourceType)) {
            return ImageBlock.builder()
                    .source(
                            Base64Source.builder()
                                    .mediaType(ProtocolJsonUtils.textValue(source, "media_type"))
                                    .data(ProtocolJsonUtils.textValue(source, "data"))
                                    .build())
                    .build();
        }
        return TextBlock.builder().text("[Unsupported image]").build();
    }

    private String systemText(JsonNode system) {
        if (system == null || system.isNull()) {
            return null;
        }
        if (system.isTextual()) {
            return system.asText();
        }
        return extractText(system);
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                builder.append(extractText(item));
            }
            return builder.toString();
        }
        String text = ProtocolJsonUtils.textValue(node, "text");
        return text != null ? text : node.toString();
    }

    private MsgRole roleToMsgRole(String role) {
        if (role == null || role.isBlank()) {
            return MsgRole.USER;
        }
        return switch (role.toLowerCase()) {
            case "assistant" -> MsgRole.ASSISTANT;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }
}
