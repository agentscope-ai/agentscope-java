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
package io.agentscope.core.model;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Gemini structured output normalization and metadata extraction.
 *
 * <p>This class keeps Gemini-specific structured output behavior out of
 * {@link GeminiChatModel} to reduce model class complexity while preserving
 * behavior.
 */
final class GeminiStructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(GeminiStructuredOutputHandler.class);

    /**
     * Fixes Gemini structured output responses when tools are expected.
     *
     * <p>Gemini may return JSON text instead of a tool call, or omit required fields.
     * This method normalizes those responses into a ToolUseBlock for structured output.
     */
    ChatResponse fixStructuredOutputResponse(
            ChatResponse response, GenerateOptions options, List<ToolSchema> tools) {

        if (response == null) {
            return response;
        }

        // Try to determine if this is a structured output request
        final String targetToolName;
        boolean isStructuredOutputRequest = false;

        if (options != null && options.getToolChoice() instanceof ToolChoice.Specific) {
            targetToolName = ((ToolChoice.Specific) options.getToolChoice()).toolName();
            isStructuredOutputRequest = true;
        } else if (tools != null) {
            // Fallback: check if tools contain the generate_response tool
            String foundToolName = null;
            for (ToolSchema tool : tools) {
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        tool.getName())) {
                    foundToolName = tool.getName();
                    isStructuredOutputRequest = true;
                    break;
                }
            }
            targetToolName = foundToolName;
        } else {
            targetToolName = null;
        }

        if (!isStructuredOutputRequest || targetToolName == null) {
            return response;
        }

        // Handle null or empty content lists
        if (response.getContent() == null || response.getContent().isEmpty()) {
            log.warn(
                    "Gemini returned null/empty content for structured output request (tool: {})."
                            + " Creating error response.",
                    targetToolName);
            return createEmptyStructuredOutputResponse(response, targetToolName, tools);
        }

        List<ContentBlock> blocks = response.getContent();

        ToolUseBlock targetToolUse = null;
        boolean targetToolCalled = false;
        boolean anyOtherToolCalled = false;
        ToolUseBlock firstHallucinatedToolUse = null;
        boolean hasHallucinatedToolCall = false;
        List<String> allowedToolNames = new ArrayList<>();
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (tool != null && tool.getName() != null) {
                    allowedToolNames.add(tool.getName());
                }
            }
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock toolUse) {
                if (targetToolName.equals(toolUse.getName())) {
                    targetToolCalled = true;
                    targetToolUse = toolUse;
                } else {
                    // A different tool was called (e.g., "add" before "generate_response")
                    anyOtherToolCalled = true;
                    if (!allowedToolNames.isEmpty()
                            && !allowedToolNames.contains(toolUse.getName())) {
                        hasHallucinatedToolCall = true;
                        if (firstHallucinatedToolUse == null) {
                            firstHallucinatedToolUse = toolUse;
                        }
                    }
                }
            }
        }

        // Gemini 3 Flash may hallucinate tool names not present in the tool list.
        // If that happens during structured output, coerce the hallucinated tool call
        // into a generate_response call so structured output metadata is populated.
        if (hasHallucinatedToolCall && !targetToolCalled && firstHallucinatedToolUse != null) {
            Map<String, Object> inputMap =
                    firstHallucinatedToolUse.getInput() != null
                            ? new HashMap<>(firstHallucinatedToolUse.getInput())
                            : new HashMap<>();
            Map<String, Object> normalized =
                    normalizeStructuredOutputInput(inputMap, tools, targetToolName);
            if (normalized == null || normalized.isEmpty()) {
                return createEmptyStructuredOutputResponse(response, targetToolName, tools);
            }

            Map<String, Object> metadata = new HashMap<>();
            if (firstHallucinatedToolUse.getMetadata() != null) {
                metadata.putAll(firstHallucinatedToolUse.getMetadata());
            }
            metadata.put("synthetic", true);
            metadata.put("hallucinated_tool", firstHallucinatedToolUse.getName());

            ToolUseBlock fixedToolUse =
                    ToolUseBlock.builder()
                            .id(firstHallucinatedToolUse.getId())
                            .name(targetToolName)
                            .input(normalized)
                            .content(JsonUtils.getJsonCodec().toJson(normalized))
                            .metadata(metadata)
                            .build();

            List<ContentBlock> newBlocks = new ArrayList<>(blocks);
            int index = newBlocks.indexOf(firstHallucinatedToolUse);
            if (index >= 0) {
                newBlocks.set(index, fixedToolUse);
            } else {
                newBlocks.add(0, fixedToolUse);
            }

            return ChatResponse.builder()
                    .id(response.getId())
                    .content(newBlocks)
                    .usage(response.getUsage())
                    .finishReason(response.getFinishReason())
                    .metadata(response.getMetadata())
                    .build();
        }

        // If a different tool was called (not generate_response), don't apply structured output
        // fixups.
        // The agent will execute that tool first and call generate_response later.
        if (anyOtherToolCalled && !targetToolCalled) {
            log.debug(
                    "Other tool called, skipping structured output fixup. generate_response will be"
                            + " called later.");
            return response;
        }

        if (targetToolCalled) {
            Map<String, Object> input = targetToolUse != null ? targetToolUse.getInput() : null;
            boolean missingInput = input == null || input.isEmpty();
            boolean missingResponseWrapper = false;
            if (!missingInput && tools != null) {
                for (ToolSchema tool : tools) {
                    if (!targetToolName.equals(tool.getName())) {
                        continue;
                    }
                    Map<String, Object> parameters = tool.getParameters();
                    if (parameters == null || !parameters.containsKey("properties")) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties =
                            (Map<String, Object>) parameters.get("properties");
                    if (properties != null) {
                        boolean usesResponseWrapper =
                                properties.containsKey("response")
                                        && (properties.size() == 1
                                                || isRequired(parameters, "response"));
                        missingResponseWrapper =
                                usesResponseWrapper && !input.containsKey("response");
                    }
                    break;
                }
            }

            if (missingInput || missingResponseWrapper) {
                String textContent = extractTextFromBlocks(blocks);
                Map<String, Object> extracted =
                        extractStructuredOutputFromText(textContent, tools, targetToolName);
                if (extracted != null && !extracted.isEmpty()) {
                    Map<String, Object> normalized =
                            normalizeStructuredOutputInput(extracted, tools, targetToolName);
                    ToolUseBlock fixedToolUse =
                            ToolUseBlock.builder()
                                    .id(targetToolUse.getId())
                                    .name(targetToolUse.getName())
                                    .input(normalized)
                                    .content(JsonUtils.getJsonCodec().toJson(normalized))
                                    .metadata(targetToolUse.getMetadata())
                                    .build();
                    List<ContentBlock> newBlocks = new ArrayList<>(blocks);
                    int index = newBlocks.indexOf(targetToolUse);
                    if (index >= 0) {
                        newBlocks.set(index, fixedToolUse);
                        return ChatResponse.builder()
                                .id(response.getId())
                                .content(newBlocks)
                                .usage(response.getUsage())
                                .finishReason(response.getFinishReason())
                                .metadata(response.getMetadata())
                                .build();
                    }
                }
            }

            return response;
        }

        TextBlock textBlock = null;
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock) {
                textBlock = (TextBlock) b;
                break;
            }
        }

        // Handle empty content case - create error response with structured output
        if (textBlock == null) {
            log.warn(
                    "Gemini returned no text content for structured output request. Creating error"
                            + " response.");
            return createEmptyStructuredOutputResponse(response, targetToolName, tools);
        }

        String textContent = textBlock.getText();
        if (textContent == null || textContent.trim().isEmpty()) {
            log.warn(
                    "Gemini returned empty text for structured output request. Creating error"
                            + " response.");
            return createEmptyStructuredOutputResponse(response, targetToolName, tools);
        }

        String trimmed = textContent.trim();
        boolean looksLikeJson =
                (trimmed.startsWith("{") && trimmed.endsWith("}"))
                        || (trimmed.startsWith("[") && trimmed.endsWith("]"));

        if (!looksLikeJson && trimmed.contains("```")) {
            int startIndex = trimmed.indexOf("```");
            int endIndex = trimmed.lastIndexOf("```");
            if (startIndex != -1 && endIndex > startIndex) {
                String extracted = trimmed.substring(startIndex + 3, endIndex);
                if (extracted.startsWith("json")) {
                    extracted = extracted.substring(4);
                }
                textContent = extracted.trim();
                looksLikeJson =
                        (textContent.startsWith("{") && textContent.endsWith("}"))
                                || (textContent.startsWith("[") && textContent.endsWith("]"));
            }
        }

        try {
            Map<String, Object> inputMap;
            if (looksLikeJson) {
                log.info(
                        "Attempting to fix Gemini response: converting text to tool call '{}'",
                        targetToolName);

                Object parsed = JsonUtils.getJsonCodec().fromJson(textContent, Object.class);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    inputMap = new HashMap<>(map);
                } else {
                    log.warn(
                            "Parsed JSON is not a Map, skipping fix. Type: {}",
                            parsed.getClass().getName());
                    return response;
                }
            } else {
                inputMap = extractStructuredOutputFromText(textContent, tools, targetToolName);
                if (inputMap == null || inputMap.isEmpty()) {
                    return response;
                }
                log.info(
                        "Attempting to fix Gemini response: parsed structured output from text for"
                                + " tool '{}'",
                        targetToolName);
            }

            inputMap = normalizeStructuredOutputInput(inputMap, tools, targetToolName);

            String callId =
                    "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(callId)
                            .name(targetToolName)
                            .input(inputMap)
                            .content(JsonUtils.getJsonCodec().toJson(inputMap))
                            .metadata(Map.of("synthetic", true))
                            .build();

            List<ContentBlock> newBlocks = new ArrayList<>();
            newBlocks.add(toolUse);

            return ChatResponse.builder()
                    .id(response.getId())
                    .content(newBlocks)
                    .usage(response.getUsage())
                    .finishReason(response.getFinishReason())
                    .metadata(response.getMetadata())
                    .build();

        } catch (Exception e) {
            log.warn("Failed to fix Gemini response: {}", e.getMessage());
            return response;
        }
    }

    private Map<String, Object> normalizeStructuredOutputInput(
            Map<String, Object> inputMap, List<ToolSchema> tools, String targetToolName) {
        if (inputMap == null) {
            return null;
        }
        Map<String, Object> normalized = new HashMap<>(inputMap);
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (!targetToolName.equals(tool.getName())) {
                    continue;
                }

                Map<String, Object> parameters = tool.getParameters();
                if (parameters == null || !parameters.containsKey("properties")) {
                    break;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
                if (properties == null) {
                    break;
                }

                boolean usesResponseWrapper =
                        properties.containsKey("response")
                                && (properties.size() == 1 || isRequired(parameters, "response"));

                if (usesResponseWrapper && !normalized.containsKey("response")) {
                    Map<String, Object> wrappedInput = new HashMap<>();
                    wrappedInput.put("response", new HashMap<>(normalized));
                    normalized = wrappedInput;
                    log.debug(
                            "Wrapped Gemini response in 'response' property for tool schema"
                                    + " compatibility");
                }

                for (String key : properties.keySet()) {
                    if (!normalized.containsKey(key)) {
                        Object defaultValue = getDefaultValueForSchemaType(properties.get(key));
                        normalized.put(key, defaultValue);
                        log.debug("Added missing field '{}' with default: {}", key, defaultValue);
                    }
                }

                if (usesResponseWrapper && properties.get("response") instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> responseSchema =
                            (Map<String, Object>) properties.get("response");
                    Object responsePropsObj = responseSchema.get("properties");
                    if (responsePropsObj instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseProps = (Map<String, Object>) responsePropsObj;
                        Object responseValue = normalized.get("response");
                        if (responseValue instanceof Map<?, ?> responseMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typedResponse =
                                    new HashMap<>((Map<String, Object>) responseMap);
                            for (String key : responseProps.keySet()) {
                                if (!typedResponse.containsKey(key)) {
                                    Object defaultValue =
                                            getDefaultValueForSchemaType(responseProps.get(key));
                                    typedResponse.put(key, defaultValue);
                                    log.debug(
                                            "Added missing response field '{}' with default: {}",
                                            key,
                                            defaultValue);
                                }
                            }
                            normalized.put("response", typedResponse);
                        }
                    }
                }
                break;
            }
        }
        return normalized;
    }

    private Map<String, Object> extractStructuredOutputFromText(
            String textContent, List<ToolSchema> tools, String targetToolName) {
        if (textContent == null || textContent.isBlank()) {
            return null;
        }
        if (tools == null) {
            return null;
        }

        Map<String, Object> properties = null;
        boolean usesResponseWrapper = false;
        for (ToolSchema tool : tools) {
            if (!targetToolName.equals(tool.getName())) {
                continue;
            }
            Map<String, Object> parameters = tool.getParameters();
            if (parameters == null || !parameters.containsKey("properties")) {
                break;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) parameters.get("properties");
            if (props == null) {
                break;
            }
            usesResponseWrapper =
                    props.containsKey("response")
                            && (props.size() == 1 || isRequired(parameters, "response"));
            if (usesResponseWrapper && props.get("response") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseSchema = (Map<String, Object>) props.get("response");
                Object responsePropsObj = responseSchema.get("properties");
                if (responsePropsObj instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> responseProps = (Map<String, Object>) responsePropsObj;
                    properties = responseProps;
                }
            } else {
                properties = props;
            }
            break;
        }

        if (properties == null || properties.isEmpty()) {
            return null;
        }

        Map<String, Object> extracted = new HashMap<>();
        boolean foundAny = false;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Object schemaProperty = entry.getValue();
            Object value = extractValueForKey(textContent, key, schemaProperty);
            if (value != null) {
                extracted.put(key, value);
                foundAny = true;
            }
        }

        if (!foundAny) {
            return null;
        }

        if (usesResponseWrapper) {
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("response", extracted);
            return wrapped;
        }
        return extracted;
    }

    private String extractTextFromBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && !text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text.trim());
                }
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private Object extractValueForKey(String text, String key, Object schemaProperty) {
        if (text == null || key == null || key.isBlank()) {
            return null;
        }
        String pattern =
                "(?is)(?:^|\\R)\\s*(?:[-*]\\s*)?(?:\\*\\*)?"
                        + java.util.regex.Pattern.quote(key)
                        + "(?:\\*\\*)?\\s*[:：]\\s*(.+?)(?=\\R|$)";
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher matcher = regex.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String rawValue = matcher.group(1).trim();
        if (rawValue.isEmpty()) {
            return null;
        }

        String type = getSchemaType(schemaProperty);
        if (type == null) {
            return rawValue;
        }

        switch (type) {
            case "integer" -> {
                Integer parsed = parseFirstInteger(rawValue);
                return parsed != null ? parsed : rawValue;
            }
            case "number" -> {
                Double parsed = parseFirstDouble(rawValue);
                return parsed != null ? parsed : rawValue;
            }
            case "boolean" -> {
                Boolean parsed = parseBoolean(rawValue);
                return parsed != null ? parsed : rawValue;
            }
            case "array" -> {
                return parseArrayValue(rawValue, schemaProperty);
            }
            case "object" -> {
                Map<String, Object> parsed = parseJsonObject(rawValue);
                return parsed != null ? parsed : rawValue;
            }
            default -> {
                return rawValue;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String getSchemaType(Object schemaProperty) {
        if (schemaProperty instanceof Map<?, ?>) {
            Map<String, Object> schema = (Map<String, Object>) schemaProperty;
            Object typeObj = schema.get("type");
            if (typeObj instanceof String type) {
                return type.toLowerCase();
            }
        }
        return null;
    }

    private Integer parseFirstInteger(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+").matcher(value);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double parseFirstDouble(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(value);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("true") || normalized.startsWith("yes")) {
            return true;
        }
        if (normalized.startsWith("false") || normalized.startsWith("no")) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object parseArrayValue(String rawValue, Object schemaProperty) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        Object itemsType = null;
        if (schemaProperty instanceof Map<?, ?>) {
            Map<String, Object> schema = (Map<String, Object>) schemaProperty;
            itemsType = schema.get("items");
        }
        String itemType = getSchemaType(itemsType);

        List<Object> results = new ArrayList<>();
        if ("integer".equals(itemType)) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("-?\\d+").matcher(trimmed);
            while (matcher.find()) {
                try {
                    results.add(Integer.parseInt(matcher.group()));
                } catch (NumberFormatException ignored) {
                    // Skip invalid numbers
                }
            }
        } else if ("number".equals(itemType)) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(trimmed);
            while (matcher.find()) {
                try {
                    results.add(Double.parseDouble(matcher.group()));
                } catch (NumberFormatException ignored) {
                    // Skip invalid numbers
                }
            }
        } else {
            String cleaned = trimmed.replace("[", "").replace("]", "");
            String[] parts = cleaned.split("[,;]");
            for (String part : parts) {
                String item = part.trim();
                if (!item.isEmpty()) {
                    results.add(item);
                }
            }
        }

        return results.isEmpty() ? null : results;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            Object parsed = JsonUtils.getJsonCodec().fromJson(trimmed, Object.class);
            if (parsed instanceof Map) {
                return new HashMap<>((Map<String, Object>) parsed);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    ChatResponse ensureStructuredOutputMetadata(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return response;
        }
        if (response.getMetadata() != null
                && response.getMetadata().containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT)) {
            return response;
        }

        Object structuredOutput = null;
        ToolUseBlock originalToolUse = null;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock toolUse
                    && StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                            toolUse.getName())) {
                Map<String, Object> input = toolUse.getInput();
                log.debug(
                        "Found generate_response tool call, input keys: {}",
                        input != null ? input.keySet() : "null");
                if (input != null && !input.isEmpty()) {
                    // Gemini returns data without "response" wrapper, but the tool expects it.
                    // Extract the actual data: if input has "response" key, use it; otherwise
                    // wrap the entire input as "response" for compatibility with the tool schema.
                    Object responseValue = input.get("response");
                    structuredOutput = responseValue != null ? responseValue : input;
                    originalToolUse = toolUse;
                    log.info(
                            "Extracted structured output from generate_response:"
                                    + " hasResponseWrapper={}",
                            responseValue != null);
                }
                break;
            }
        }

        if (structuredOutput == null) {
            log.debug("No structured output found in response");
            return response;
        }

        // If Gemini returned unwrapped data, we need to wrap it for the tool to process correctly
        List<ContentBlock> fixedContent = new ArrayList<>(response.getContent());
        if (originalToolUse != null && !originalToolUse.getInput().containsKey("response")) {
            // Wrap the input in "response" for compatibility with StructuredOutputCapableAgent
            Map<String, Object> wrappedInput = Map.of("response", structuredOutput);
            ToolUseBlock fixedToolUse =
                    ToolUseBlock.builder()
                            .id(originalToolUse.getId())
                            .name(originalToolUse.getName())
                            .input(wrappedInput)
                            .content(JsonUtils.getJsonCodec().toJson(wrappedInput))
                            .metadata(originalToolUse.getMetadata())
                            .build();
            int index = fixedContent.indexOf(originalToolUse);
            if (index >= 0) {
                fixedContent.set(index, fixedToolUse);
                log.info("Wrapped Gemini tool call input in 'response' property");
            }
        }

        Map<String, Object> metadata =
                new HashMap<>(response.getMetadata() != null ? response.getMetadata() : Map.of());
        metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredOutput);

        return ChatResponse.builder()
                .id(response.getId())
                .content(fixedContent)
                .usage(response.getUsage())
                .finishReason(response.getFinishReason())
                .metadata(metadata)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static boolean isRequired(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return false;
        }
        Object required = parameters.get("required");
        if (required instanceof List<?> requiredList) {
            return requiredList.contains(key);
        }
        return false;
    }

    /**
     * Returns a type-appropriate default value based on JSON Schema type.
     */
    @SuppressWarnings("unchecked")
    private static Object getDefaultValueForSchemaType(Object schemaProperty) {
        if (schemaProperty instanceof Map<?, ?>) {
            Map<String, Object> schema = (Map<String, Object>) schemaProperty;
            Object typeObj = schema.get("type");
            if (typeObj instanceof String type) {
                return switch (type.toLowerCase()) {
                    case "string" -> "";
                    case "number", "integer" -> 0;
                    case "boolean" -> false;
                    case "array" -> new ArrayList<>();
                    case "object" -> new HashMap<>();
                    default -> "";
                };
            }
        }
        // Default to empty string for unknown types (most common in Gemini responses)
        return "";
    }

    /**
     * Creates a structured output response with empty/default values when Gemini returns no
     * content. This ensures that the structured output metadata key can still be populated.
     */
    private ChatResponse createEmptyStructuredOutputResponse(
            ChatResponse response, String targetToolName, List<ToolSchema> tools) {

        Map<String, Object> inputMap = new HashMap<>();

        // Find the tool schema and populate all required fields with defaults
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (!targetToolName.equals(tool.getName())) {
                    continue;
                }

                Map<String, Object> parameters = tool.getParameters();
                if (parameters != null && parameters.containsKey("properties")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties =
                            (Map<String, Object>) parameters.get("properties");
                    if (properties != null) {
                        // Check if this uses a response wrapper
                        boolean usesResponseWrapper =
                                properties.containsKey("response")
                                        && (properties.size() == 1
                                                || isRequired(parameters, "response"));

                        if (usesResponseWrapper
                                && properties.get("response") instanceof Map<?, ?>) {
                            // Create nested structure
                            @SuppressWarnings("unchecked")
                            Map<String, Object> responseSchema =
                                    (Map<String, Object>) properties.get("response");
                            Object responsePropsObj = responseSchema.get("properties");
                            if (responsePropsObj instanceof Map<?, ?>) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> responseProps =
                                        (Map<String, Object>) responsePropsObj;
                                Map<String, Object> responseMap = new HashMap<>();
                                for (String key : responseProps.keySet()) {
                                    responseMap.put(
                                            key,
                                            getDefaultValueForSchemaType(responseProps.get(key)));
                                }
                                inputMap.put("response", responseMap);
                            } else {
                                inputMap.put("response", new HashMap<String, Object>());
                            }
                        } else {
                            // Flat structure - populate all properties with type-appropriate
                            // defaults
                            for (String key : properties.keySet()) {
                                inputMap.put(
                                        key, getDefaultValueForSchemaType(properties.get(key)));
                            }
                        }
                    }
                }
                break;
            }
        }

        // If we couldn't determine the schema structure, create a simple response wrapper
        if (inputMap.isEmpty()) {
            inputMap.put("response", Map.of("error", "Model returned no content"));
        }

        String callId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        // Mark this as a synthetic tool call
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("synthetic", true);
        metadata.put("empty_response", true);

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id(callId)
                        .name(targetToolName)
                        .input(inputMap)
                        .content(JsonUtils.getJsonCodec().toJson(inputMap))
                        .metadata(metadata)
                        .build();

        List<ContentBlock> newBlocks = new ArrayList<>();
        newBlocks.add(toolUse);

        return ChatResponse.builder()
                .id(response.getId())
                .content(newBlocks)
                .usage(response.getUsage())
                .finishReason(response.getFinishReason())
                .metadata(response.getMetadata())
                .build();
    }
}
