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
package io.agentscope.core.llm.interfacesweb.responses;

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

/** Converts stateless OpenAI Responses requests into AgentScope messages. */
public class ResponsesMessageConverter {

    public List<Msg> convert(ResponsesRequest request) {
        validateStatelessSubset(request);

        List<Msg> messages = new ArrayList<>();
        if (request.getInstructions() != null && !request.getInstructions().isBlank()) {
            messages.add(
                    ProtocolMessageUtils.textMessage(MsgRole.SYSTEM, request.getInstructions()));
        }

        JsonNode input = ProtocolJsonUtils.toJsonNode(request.getInput());
        if (input == null || input.isNull()) {
            throw new ProtocolException("invalid_request_error", "Responses input is required");
        }
        if (input.isTextual()) {
            messages.add(ProtocolMessageUtils.textMessage(MsgRole.USER, input.asText()));
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                Msg msg = convertInputItem(item);
                if (msg != null) {
                    messages.add(msg);
                }
            }
        } else if (input.isObject()) {
            messages.add(convertInputItem(input));
        } else {
            throw new ProtocolException(
                    "invalid_request_error", "Responses input must be a string or an array");
        }

        if (messages.isEmpty()) {
            throw new ProtocolException(
                    "invalid_request_error", "At least one input message is required");
        }
        return messages;
    }

    private void validateStatelessSubset(ResponsesRequest request) {
        if (request.getPreviousResponseId() != null && !request.getPreviousResponseId().isBlank()) {
            throw unsupported("previous_response_id is not supported by this stateless endpoint");
        }
        if (request.getConversation() != null) {
            throw unsupported("conversation is not supported by this stateless endpoint");
        }
        if (Boolean.TRUE.equals(request.getBackground())) {
            throw unsupported("background responses are not supported by this stateless endpoint");
        }
    }

    private ProtocolException unsupported(String message) {
        return new ProtocolException("unsupported_feature", message);
    }

    private Msg convertInputItem(JsonNode item) {
        if (item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return ProtocolMessageUtils.textMessage(MsgRole.USER, item.asText());
        }

        String type = ProtocolJsonUtils.textValue(item, "type");
        if ("function_call_output".equals(type)) {
            return convertFunctionCallOutput(item);
        }
        if ("function_call".equals(type)) {
            return convertFunctionCall(item);
        }

        String role = ProtocolJsonUtils.textValue(item, "role");
        MsgRole msgRole = roleToMsgRole(role);
        List<ContentBlock> content = convertContent(item.get("content"));
        if (content.isEmpty()) {
            String text = ProtocolJsonUtils.textValue(item, "text");
            if (text == null) {
                text = ProtocolJsonUtils.textValue(item, "output_text");
            }
            content.add(TextBlock.builder().text(text != null ? text : "").build());
        }
        return ProtocolMessageUtils.message(msgRole, content);
    }

    private Msg convertFunctionCall(JsonNode item) {
        String callId =
                firstNonBlank(
                        ProtocolJsonUtils.textValue(item, "call_id"),
                        ProtocolJsonUtils.textValue(item, "id"));
        String name = ProtocolJsonUtils.textValue(item, "name");
        String arguments = ProtocolJsonUtils.textValue(item, "arguments");
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id(callId)
                        .name(name)
                        .input(ProtocolJsonUtils.parseRequiredObject(arguments, "arguments"))
                        .content(arguments)
                        .build();
        return ProtocolMessageUtils.message(MsgRole.ASSISTANT, List.of(block));
    }

    private Msg convertFunctionCallOutput(JsonNode item) {
        String callId = ProtocolJsonUtils.textValue(item, "call_id");
        String name = ProtocolJsonUtils.textValue(item, "name");
        String output = ProtocolJsonUtils.textValue(item, "output");
        if (output == null && item.has("content")) {
            output = extractText(item.get("content"));
        }
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id(callId)
                        .name(name)
                        .output(TextBlock.builder().text(output != null ? output : "").build())
                        .build();
        return ProtocolMessageUtils.message(MsgRole.TOOL, List.of(block));
    }

    private List<ContentBlock> convertContent(JsonNode contentNode) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (contentNode == null || contentNode.isNull()) {
            return blocks;
        }
        if (contentNode.isTextual()) {
            blocks.add(TextBlock.builder().text(contentNode.asText()).build());
            return blocks;
        }
        if (contentNode.isArray()) {
            for (JsonNode part : contentNode) {
                ContentBlock block = convertContentPart(part);
                if (block != null) {
                    blocks.add(block);
                }
            }
            return blocks;
        }
        ContentBlock block = convertContentPart(contentNode);
        blocks.add(block);
        return blocks;
    }

    private ContentBlock convertContentPart(JsonNode part) {
        if (part.isNull()) {
            return null;
        }
        if (part.isTextual()) {
            return TextBlock.builder().text(part.asText()).build();
        }
        String type = ProtocolJsonUtils.textValue(part, "type");
        if ("input_text".equals(type) || "output_text".equals(type) || "text".equals(type)) {
            return TextBlock.builder()
                    .text(firstNonBlank(ProtocolJsonUtils.textValue(part, "text"), ""))
                    .build();
        }
        if ("input_image".equals(type) || "image".equals(type)) {
            return imageBlock(part);
        }
        if ("tool_use".equals(type)) {
            String inputJson =
                    part.has("input") ? ProtocolJsonUtils.toJson(part.get("input")) : "{}";
            return ToolUseBlock.builder()
                    .id(ProtocolJsonUtils.textValue(part, "id"))
                    .name(ProtocolJsonUtils.textValue(part, "name"))
                    .input(
                            part.has("input")
                                    ? ProtocolJsonUtils.toMap(part.get("input"))
                                    : Map.of())
                    .content(inputJson)
                    .build();
        }
        return TextBlock.builder().text(extractText(part)).build();
    }

    private ContentBlock imageBlock(JsonNode part) {
        String imageUrl = ProtocolJsonUtils.textValue(part, "image_url");
        if (imageUrl == null) {
            imageUrl = ProtocolJsonUtils.textValue(part, "url");
        }
        if (imageUrl != null && imageUrl.startsWith("data:")) {
            int commaIndex = imageUrl.indexOf(',');
            String metadata =
                    commaIndex >= 0 ? imageUrl.substring(5, commaIndex) : "image/png;base64";
            String mediaType = metadata.replace(";base64", "");
            String data = commaIndex >= 0 ? imageUrl.substring(commaIndex + 1) : "";
            return ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mediaType).data(data).build())
                    .build();
        }
        if (imageUrl != null) {
            return ImageBlock.builder().source(URLSource.builder().url(imageUrl).build()).build();
        }
        return TextBlock.builder().text("[Unsupported image]").build();
    }

    private MsgRole roleToMsgRole(String role) {
        if (role == null || role.isBlank()) {
            return MsgRole.USER;
        }
        return switch (role.toLowerCase()) {
            case "system", "developer" -> MsgRole.SYSTEM;
            case "assistant" -> MsgRole.ASSISTANT;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
