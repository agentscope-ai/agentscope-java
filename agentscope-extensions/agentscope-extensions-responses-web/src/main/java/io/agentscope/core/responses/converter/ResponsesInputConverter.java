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
package io.agentscope.core.responses.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesTextConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts Responses request input into AgentScope messages. */
public class ResponsesInputConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Convert a Responses request into AgentScope messages and structured-output metadata.
     *
     * <p>Responses input can be a string, a single item, or an array of items. This method normalizes
     * those shapes into AgentScope messages while preserving instruction-like content for the
     * request hook.
     *
     * @param request Original Responses request
     * @return Converted messages plus request-scoped metadata
     */
    public ResponsesConversionResult convert(ResponsesRequest request) {
        validateRequest(request);

        List<Msg> messages = new ArrayList<>();
        List<String> systemFragments = new ArrayList<>();
        if (request.getInstructions() != null && !request.getInstructions().isBlank()) {
            systemFragments.add(request.getInstructions());
        }

        // Keep instructions/system/developer content out of the chat message list so the hook can
        // append it through AgentScope's system prompt mechanism.
        convertInput(jsonNode(request.getInput()), messages, systemFragments, "input");
        if (messages.isEmpty()) {
            throw ResponsesValidationException.invalid(
                    "At least one non-system input item is required", "input");
        }

        String textFormat = textFormatType(request);
        JsonNode schema = structuredOutputSchema(request, textFormat);
        return new ResponsesConversionResult(
                List.copyOf(messages), List.copyOf(systemFragments), schema, textFormat);
    }

    private void validateRequest(ResponsesRequest request) {
        if (request == null) {
            throw ResponsesValidationException.invalid("Request body is required", null);
        }
        if (request.getInput() == null || jsonNode(request.getInput()).isNull()) {
            throw ResponsesValidationException.invalid("input is required", "input");
        }
    }

    /**
     * Resolve the requested text format.
     *
     * <p>{@code json_schema} maps to AgentScope structured output. {@code json_object} is rejected
     * until the model adapter has an explicit JSON-object mode that is distinct from schema output.
     */
    private String textFormatType(ResponsesRequest request) {
        ResponsesTextConfig text = request.getText();
        if (text == null || text.getFormat() == null || text.getFormat().getType() == null) {
            return "text";
        }
        String type = text.getFormat().getType();
        if ("text".equals(type) || "json_schema".equals(type)) {
            return type;
        }
        if ("json_object".equals(type)) {
            throw ResponsesValidationException.unsupported(
                    "text.format.type=json_object is not supported in Responses API v1",
                    "text.format.type");
        }
        throw ResponsesValidationException.unsupported(
                "Unsupported text.format.type: " + type, "text.format.type");
    }

    /** Extract and validate the JSON Schema used for structured output. */
    private JsonNode structuredOutputSchema(ResponsesRequest request, String textFormat) {
        if (!"json_schema".equals(textFormat)) {
            return null;
        }
        JsonNode schema = jsonNode(request.getText().getFormat().getSchema());
        if (schema == null || !schema.isObject()) {
            throw ResponsesValidationException.invalid(
                    "text.format.schema must be a JSON object", "text.format.schema");
        }
        return schema;
    }

    private void convertInput(
            JsonNode input, List<Msg> messages, List<String> systemFragments, String param) {
        if (input.isTextual()) {
            messages.add(userMessage(List.of(text(input.asText()))));
            return;
        }
        if (input.isArray()) {
            for (int i = 0; i < input.size(); i++) {
                convertInputItem(input.get(i), messages, systemFragments, param + "[" + i + "]");
            }
            return;
        }
        if (input.isObject()) {
            convertInputItem(input, messages, systemFragments, param);
            return;
        }
        throw ResponsesValidationException.invalid(
                "input must be a string, object, or array", param);
    }

    private void convertInputItem(
            JsonNode item, List<Msg> messages, List<String> systemFragments, String param) {
        if (item == null || !item.isObject()) {
            throw ResponsesValidationException.invalid("Input item must be an object", param);
        }
        String type = textValue(item.get("type"));
        if ("function_call".equals(type)) {
            messages.add(functionCallMessage(item, param));
            return;
        }
        if ("function_call_output".equals(type)) {
            messages.add(functionCallOutputMessage(item, param));
            return;
        }
        if (type != null && !"message".equals(type)) {
            // New official Responses item types can arrive before AgentScope has a native block for
            // them. Preserve the raw JSON as text so context is not silently dropped.
            messages.add(opaqueItemMessage(item, type));
            return;
        }

        String role = textValue(item.get("role"));
        if (role == null || role.isBlank()) {
            throw ResponsesValidationException.invalid("Input message role is required", param);
        }
        List<ContentBlock> content = contentBlocks(item.get("content"), param + ".content");
        switch (role) {
            case "user" -> messages.add(Msg.builder().role(MsgRole.USER).content(content).build());
            case "assistant" ->
                    messages.add(Msg.builder().role(MsgRole.ASSISTANT).content(content).build());
            case "system", "developer" ->
                    // AgentScope has a dedicated system prompt path. Developer messages map there
                    // too because both roles are instruction-like in the Responses API.
                    systemFragments.add(textOnly(content, param));
            default ->
                    throw ResponsesValidationException.unsupported(
                            "Unsupported input message role: " + role, param + ".role");
        }
    }

    private Msg userMessage(List<ContentBlock> content) {
        return Msg.builder().role(MsgRole.USER).content(content).build();
    }

    private Msg functionCallMessage(JsonNode item, String param) {
        String callId = textValue(item.get("call_id"));
        if (callId == null || callId.isBlank()) {
            throw ResponsesValidationException.invalid("function_call.call_id is required", param);
        }
        String name = textValue(item.get("name"));
        if (name == null || name.isBlank()) {
            throw ResponsesValidationException.invalid("function_call.name is required", param);
        }
        JsonNode argumentsNode = item.get("arguments");
        String arguments = argumentsAsString(argumentsNode);
        // Keep both parsed and raw argument forms. The parsed map is convenient for AgentScope
        // tools, while the raw string preserves provider-specific JSON exactly for Responses IO.
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id(callId)
                        .name(name)
                        .input(argumentsAsMap(argumentsNode))
                        .content(arguments)
                        .build();
        return Msg.builder().role(MsgRole.ASSISTANT).content(block).build();
    }

    private Msg functionCallOutputMessage(JsonNode item, String param) {
        String callId = textValue(item.get("call_id"));
        if (callId == null || callId.isBlank()) {
            throw ResponsesValidationException.invalid(
                    "function_call_output.call_id is required", param);
        }
        List<ContentBlock> output = contentBlocks(item.get("output"), param + ".output");
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id(callId).output(output).build())
                .build();
    }

    private List<ContentBlock> contentBlocks(JsonNode content, String param) {
        if (content == null || content.isNull()) {
            return List.of(text(""));
        }
        if (content.isTextual()) {
            return List.of(text(content.asText()));
        }
        if (content.isObject()) {
            return List.of(contentPart(content, param));
        }
        if (!content.isArray()) {
            throw ResponsesValidationException.invalid(
                    "Content must be a string, object, or array", param);
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            blocks.add(contentPart(content.get(i), param + "[" + i + "]"));
        }
        return blocks;
    }

    private ContentBlock contentPart(JsonNode part, String param) {
        if (part == null || !part.isObject()) {
            throw ResponsesValidationException.invalid("Content part must be an object", param);
        }
        String type = textValue(part.get("type"));
        if ("input_text".equals(type) || "output_text".equals(type)) {
            return text(textValue(part.get("text")));
        }
        if ("input_image".equals(type)) {
            if (part.hasNonNull("file_id")) {
                // file_id is a reference to an application file service. Without that service in
                // the generic starter, preserve it as opaque text instead of pretending to load it.
                return text(opaquePartText(part));
            }
            String imageUrl = textValue(part.get("image_url"));
            if (imageUrl == null || imageUrl.isBlank()) {
                throw ResponsesValidationException.invalid(
                        "input_image.image_url is required", param + ".image_url");
            }
            return image(imageUrl, param + ".image_url");
        }
        if ("input_audio".equals(type)) {
            ContentBlock audio = audio(part);
            // Unknown audio shapes are still retained as opaque text so future official fields do
            // not disappear from conversation context.
            return audio != null ? audio : text(opaquePartText(part));
        }
        if ("input_video".equals(type)) {
            ContentBlock video = video(part);
            // Same fallback as audio: accept the item shape, but only native URL/base64 fields
            // become AgentScope media blocks.
            return video != null ? video : text(opaquePartText(part));
        }
        if ("input_file".equals(type) || type == null) {
            return text(opaquePartText(part));
        }
        return text(opaquePartText(part));
    }

    private Msg opaqueItemMessage(JsonNode item, String type) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(text("Responses item " + type + ": " + toJson(item)))
                .build();
    }

    private TextBlock text(String value) {
        return TextBlock.builder().text(value != null ? value : "").build();
    }

    private ImageBlock image(String imageUrl, String param) {
        if (imageUrl.startsWith("data:")) {
            int comma = imageUrl.indexOf(',');
            int separator = imageUrl.indexOf(";base64,");
            if (separator <= 5 || comma < 0 || separator + 7 != comma) {
                throw ResponsesValidationException.invalid("Invalid image data URL", param);
            }
            String mediaType = imageUrl.substring(5, separator);
            String data = imageUrl.substring(comma + 1);
            return ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mediaType).data(data).build())
                    .build();
        }
        return ImageBlock.builder().source(URLSource.builder().url(imageUrl).build()).build();
    }

    private ContentBlock audio(JsonNode part) {
        JsonNode audio = objectOrSelf(part.get("input_audio"), part);
        String url = firstText(audio, "audio_url", "url");
        if (url != null && !url.isBlank()) {
            return AudioBlock.builder().source(URLSource.builder().url(url).build()).build();
        }
        String data = firstText(audio, "data", "audio_data");
        if (data != null && !data.isBlank()) {
            String format = firstText(audio, "format");
            return AudioBlock.builder()
                    .source(
                            Base64Source.builder()
                                    .mediaType(mediaType("audio", format))
                                    .data(data)
                                    .build())
                    .build();
        }
        return null;
    }

    private ContentBlock video(JsonNode part) {
        JsonNode video = objectOrSelf(part.get("input_video"), part);
        String url = firstText(video, "video_url", "url");
        if (url != null && !url.isBlank()) {
            return VideoBlock.builder().source(URLSource.builder().url(url).build()).build();
        }
        String data = firstText(video, "data", "video_data");
        if (data != null && !data.isBlank()) {
            String format = firstText(video, "format");
            return VideoBlock.builder()
                    .source(
                            Base64Source.builder()
                                    .mediaType(mediaType("video", format))
                                    .data(data)
                                    .build())
                    .build();
        }
        return null;
    }

    private String opaquePartText(JsonNode part) {
        String type = textValue(part.get("type"));
        return "Responses content part " + (type != null ? type : "unknown") + ": " + toJson(part);
    }

    private JsonNode objectOrSelf(JsonNode candidate, JsonNode fallback) {
        return candidate != null && candidate.isObject() ? candidate : fallback;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            String value = textValue(node.get(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String mediaType(String family, String format) {
        return family + "/" + (format != null && !format.isBlank() ? format : "octet-stream");
    }

    private String textOnly(List<ContentBlock> content, String param) {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(textBlock.getText());
            } else {
                throw ResponsesValidationException.unsupported(
                        "system/developer messages support text content only", param + ".content");
            }
        }
        return builder.toString();
    }

    private String argumentsAsString(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> argumentsAsMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        try {
            JsonNode parsed = node;
            if (node.isTextual()) {
                // Responses function_call.arguments is often a JSON string, while ToolUseBlock
                // expects a parsed input map for local tool execution.
                parsed = OBJECT_MAPPER.readTree(node.asText());
            }
            if (!parsed.isObject()) {
                return Map.of();
            }
            return OBJECT_MAPPER.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private JsonNode jsonNode(Object value) {
        return OBJECT_MAPPER.valueToTree(value);
    }

    private String toJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return String.valueOf(node);
        }
    }
}
