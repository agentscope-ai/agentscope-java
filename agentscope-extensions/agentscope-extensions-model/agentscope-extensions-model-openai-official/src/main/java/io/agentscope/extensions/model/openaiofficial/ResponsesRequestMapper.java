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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.core.JsonField;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Maps AgentScope inputs to OpenAI Responses API {@link ResponseCreateParams}.
 *
 * <p>Handles options mapping, message history mapping (including reasoning replay),
 * tool definition mapping, and all fail-fast validation for unsupported fields.
 */
final class ResponsesRequestMapper {

    private ResponsesRequestMapper() {}

    /**
     * Maps AgentScope messages, tools, and options to a {@link ResponseCreateParams}.
     *
     * @param messages         the conversation history
     * @param tools            the tool definitions (may be null or empty)
     * @param effectiveOptions the merged (per-call + configured) generation options
     * @param strictTools      the model-level strict tools setting (null = not set)
     * @param strictJsonSchema the model-level strict JSON schema setting (null = not set)
     * @param historyMapper function that converts messages to Responses API input items;
     *     use {@link #mapHistory(List)} for default 1:1 mapping, or pass a
     *     {@link ResponsesMultiAgentFormatter#formatHistory} method reference for
     *     multi-agent conversation merging
     * @return a built {@link ResponseCreateParams}
     */
    static ResponseCreateParams map(
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions effectiveOptions,
            Boolean strictTools,
            Boolean strictJsonSchema,
            Function<List<Msg>, List<ResponseInputItem>> historyMapper) {

        validateRejectedFields(effectiveOptions);

        Map<String, Object> additionalBodyParams = effectiveOptions.getAdditionalBodyParams();
        if (additionalBodyParams != null && !additionalBodyParams.isEmpty()) {
            validateWhitelist(additionalBodyParams);
        }

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder()
                        .store(false)
                        .model(ResponsesModel.ofString(effectiveOptions.getModelName()));

        mapOptions(builder, effectiveOptions, strictJsonSchema);

        if (additionalBodyParams != null && !additionalBodyParams.isEmpty()) {
            mapAdditionalBodyParams(builder, additionalBodyParams);
        }

        builder.inputOfResponse(historyMapper.apply(messages));

        if (tools != null && !tools.isEmpty()) {
            builder.tools(mapTools(tools, strictTools));
        }

        if (effectiveOptions.getToolChoice() != null) {
            mapToolChoice(builder, effectiveOptions.getToolChoice());
        }

        return builder.build();
    }

    // ── Options mapping ──────────────────────────────────────────

    private static void mapOptions(
            ResponseCreateParams.Builder builder,
            GenerateOptions options,
            Boolean strictJsonSchema) {

        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getTopP() != null) {
            builder.topP(options.getTopP());
        }

        Integer maxTokens = options.getMaxCompletionTokens();
        if (maxTokens == null) {
            maxTokens = options.getMaxTokens();
        }
        if (maxTokens != null) {
            builder.maxOutputTokens(maxTokens.longValue());
        }

        if (options.getParallelToolCalls() != null) {
            builder.parallelToolCalls(options.getParallelToolCalls());
        }

        if (options.getReasoningEffort() != null
                || hasWhitelistKey(options, "reasoning.summary")
                || hasWhitelistKey(options, "reasoning.context")
                || hasWhitelistKey(options, "reasoning.mode")) {
            builder.reasoning(buildReasoning(options));
        }

        if (options.getResponseFormat() != null) {
            ResponseFormat format = options.getResponseFormat();
            if (format.getType() != null) {
                switch (format.getType()) {
                    case "json_object" ->
                            builder.text(
                                    ResponseTextConfig.builder()
                                            .format(ResponseFormatJsonObject.builder().build())
                                            .build());
                    case "json_schema" -> {
                        JsonSchema schema = format.getJsonSchema();
                        if (schema == null || schema.getName() == null) {
                            throw new OpenAIOfficialModelException(
                                    "json_schema response format requires a non-null"
                                            + " schema or schema name.");
                        }
                        ResponseFormatTextJsonSchemaConfig.Builder schemaBuilder =
                                ResponseFormatTextJsonSchemaConfig.builder()
                                        .name(schema.getName())
                                        .schema(buildSchema(schema.getSchema()));
                        if (schema.getDescription() != null) {
                            schemaBuilder.description(schema.getDescription());
                        }
                        if (strictJsonSchema != null) {
                            schemaBuilder.strict(strictJsonSchema);
                        }
                        builder.text(
                                ResponseTextConfig.builder().format(schemaBuilder.build()).build());
                    }
                    case "text" -> {
                        // SDK default is plain text; no need to set text param
                    }
                    default ->
                            throw new OpenAIOfficialModelException(
                                    "Unsupported response format type: " + format.getType());
                }
            }
        }
    }

    private static boolean hasWhitelistKey(GenerateOptions options, String key) {
        Map<String, Object> params = options.getAdditionalBodyParams();
        return params != null && params.get(key) != null;
    }

    private static Reasoning buildReasoning(GenerateOptions options) {
        Reasoning.Builder builder = Reasoning.builder();
        if (options.getReasoningEffort() != null) {
            // validate() forces client-side enum checking; of() alone accepts any string
            builder.effort(ReasoningEffort.of(options.getReasoningEffort()).validate());
        }
        Map<String, Object> params = options.getAdditionalBodyParams();
        if (params != null) {
            String summary = asString(params.get("reasoning.summary"));
            if (summary != null) {
                builder.summary(Reasoning.Summary.of(summary).validate());
            }
            String context = asString(params.get("reasoning.context"));
            if (context != null) {
                builder.context(Reasoning.Context.of(context).validate());
            }
            String mode = asString(params.get("reasoning.mode"));
            if (mode != null) {
                builder.mode(Reasoning.Mode.of(mode).validate());
            }
        }
        return builder.build();
    }

    // ── AdditionalBodyParams mapping ─────────────────────────────

    private static void validateWhitelist(Map<String, Object> params) {
        for (String key : params.keySet()) {
            if (!OpenAIOfficialConstants.ADDITIONAL_BODY_PARAMS_WHITELIST.contains(key)) {
                throw new OpenAIOfficialModelException(
                        "additionalBodyParams key '"
                                + key
                                + "' is not in the whitelist for the openai-official"
                                + " provider.");
            }
        }
    }

    private static void mapAdditionalBodyParams(
            ResponseCreateParams.Builder builder, Map<String, Object> params) {

        String maxToolCalls = asString(params.get("max_tool_calls"));
        if (maxToolCalls != null) {
            try {
                builder.maxToolCalls(Long.parseLong(maxToolCalls));
            } catch (NumberFormatException e) {
                throw new OpenAIOfficialModelException(
                        "Invalid max_tool_calls value '"
                                + maxToolCalls
                                + "': expected a long integer.");
            }
        }

        String serviceTier = asString(params.get("service_tier"));
        if (serviceTier != null) {
            builder.serviceTier(ResponseCreateParams.ServiceTier.of(serviceTier).validate());
        }

        String promptCacheKey = asString(params.get("prompt_cache_key"));
        if (promptCacheKey != null) {
            builder.promptCacheKey(promptCacheKey);
        }

        Object promptCacheOptionsRaw = params.get("prompt_cache_options");
        if (promptCacheOptionsRaw != null) {
            if (!(promptCacheOptionsRaw instanceof Map<?, ?>)) {
                throw new OpenAIOfficialModelException(
                        "prompt_cache_options must be a Map, got: "
                                + promptCacheOptionsRaw.getClass().getSimpleName());
            }
            builder.promptCacheOptions(buildPromptCacheOptions((Map<?, ?>) promptCacheOptionsRaw));
        }

        String safetyIdentifier = asString(params.get("safety_identifier"));
        if (safetyIdentifier != null) {
            builder.safetyIdentifier(safetyIdentifier);
        }
    }

    private static ResponseCreateParams.PromptCacheOptions buildPromptCacheOptions(Map<?, ?> raw) {
        ResponseCreateParams.PromptCacheOptions.Builder builder =
                ResponseCreateParams.PromptCacheOptions.builder();
        String mode = asString(raw.get("mode"));
        if (mode != null) {
            builder.mode(ResponseCreateParams.PromptCacheOptions.Mode.of(mode).validate());
        }
        String ttl = asString(raw.get("ttl"));
        if (ttl != null) {
            builder.ttl(ResponseCreateParams.PromptCacheOptions.Ttl.of(ttl).validate());
        }
        return builder.build();
    }

    // ── Rejected fields validation ───────────────────────────────

    private static void validateRejectedFields(GenerateOptions options) {
        if (options.getEndpointPath() != null && !options.getEndpointPath().isBlank()) {
            throw new OpenAIOfficialModelException(
                    "endpointPath is not supported by the openai-official provider.");
        }
        if (options.getFrequencyPenalty() != null) {
            throw new OpenAIOfficialModelException(
                    "frequencyPenalty is not supported by the openai-official provider.");
        }
        if (options.getPresencePenalty() != null) {
            throw new OpenAIOfficialModelException(
                    "presencePenalty is not supported by the openai-official provider.");
        }
        if (options.getTopK() != null) {
            throw new OpenAIOfficialModelException(
                    "topK is not supported by the openai-official provider.");
        }
        if (options.getSeed() != null) {
            throw new OpenAIOfficialModelException(
                    "seed is not supported by the openai-official provider.");
        }
        if (options.getCacheControl() != null) {
            throw new OpenAIOfficialModelException(
                    "cacheControl is not supported by the openai-official provider.");
        }
        if (options.getThinkingBudget() != null) {
            throw new OpenAIOfficialModelException(
                    "thinkingBudget is not supported by the openai-official provider.");
        }
        if (options.getAdditionalHeaders() != null && !options.getAdditionalHeaders().isEmpty()) {
            throw new OpenAIOfficialModelException(
                    "per-request additionalHeaders are not supported by the"
                            + " openai-official provider. Use builder-level"
                            + " additionalHeaders instead.");
        }
        if (options.getAdditionalQueryParams() != null
                && !options.getAdditionalQueryParams().isEmpty()) {
            throw new OpenAIOfficialModelException(
                    "per-request additionalQueryParams are not supported by"
                            + " the openai-official provider.");
        }
    }

    // ── History mapping ──────────────────────────────────────────

    static List<ResponseInputItem> mapHistory(List<Msg> messages) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (Msg msg : messages) {
            mapMessage(msg, items);
        }
        return items;
    }

    static void mapMessage(Msg msg, List<ResponseInputItem> items) {
        switch (msg.getRole()) {
            case SYSTEM -> mapSystemMessage(msg, items);
            case USER -> mapUserMessage(msg, items);
            case ASSISTANT -> mapAssistantMessage(msg, items);
            case TOOL -> mapToolMessage(msg, items);
        }
    }

    static void mapSystemMessage(Msg msg, List<ResponseInputItem> items) {
        // Msg.validateRoleContent only allows TextBlock for the SYSTEM role during construction
        String text = collectText(msg);
        items.add(
                ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.SYSTEM)
                                .content(text != null ? text : "")
                                .build()));
    }

    static void mapUserMessage(Msg msg, List<ResponseInputItem> items) {
        List<?> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            items.add(
                    ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.USER)
                                    .content("")
                                    .build()));
            return;
        }

        if (blocks.size() == 1 && blocks.get(0) instanceof TextBlock tb) {
            items.add(
                    ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.USER)
                                    .content(tb.getText())
                                    .build()));
            return;
        }

        List<ResponseInputContent> parts = new ArrayList<>();
        for (Object block : blocks) {
            if (block instanceof TextBlock tb) {
                parts.add(
                        ResponseInputContent.ofInputText(
                                ResponseInputText.builder().text(tb.getText()).build()));
            } else if (block instanceof ImageBlock ib) {
                String imageUrl = resolveImageUrl(ib.getSource());
                parts.add(
                        ResponseInputContent.ofInputImage(
                                ResponseInputImage.builder()
                                        .imageUrl(imageUrl)
                                        .detail(ResponseInputImage.Detail.of("auto"))
                                        .build()));
            } else if (block instanceof DataBlock db) {
                String imageUrl = resolveDataBlockImageUrl(db);
                parts.add(
                        ResponseInputContent.ofInputImage(
                                ResponseInputImage.builder()
                                        .imageUrl(imageUrl)
                                        .detail(ResponseInputImage.Detail.of("auto"))
                                        .build()));
            } else {
                throw new OpenAIOfficialModelException(
                        "Unsupported content block in user message: "
                                + block.getClass().getSimpleName());
            }
        }
        items.add(
                ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.USER)
                                .contentOfResponseInputMessageContentList(parts)
                                .build()));
    }

    static void mapAssistantMessage(Msg msg, List<ResponseInputItem> items) {
        Map<String, Object> metadata = msg.getMetadata();
        String encryptedContent = null;
        if (metadata != null) {
            Object ec = metadata.get(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT);
            if (ec instanceof String s) {
                encryptedContent = s;
            }
        }

        if (encryptedContent != null) {
            mapReasoningReplay(msg, encryptedContent, items);
        }

        String text = collectText(msg);
        if (text != null && !text.isEmpty()) {
            items.add(
                    ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.ASSISTANT)
                                    .content(text)
                                    .build()));
        }

        List<?> blocks = msg.getContent();
        if (blocks != null) {
            for (Object block : blocks) {
                if (block instanceof ToolUseBlock tb) {
                    mapToolUseBlock(tb, items);
                } else if (block instanceof ThinkingBlock) {
                    if (encryptedContent == null) {
                        throw new OpenAIOfficialModelException(
                                "Cannot map ThinkingBlock to a Responses reasoning"
                                        + " input item without encrypted_content.");
                    }
                } else if (!(block instanceof TextBlock)) {
                    throw new OpenAIOfficialModelException(
                            "Unsupported content block in assistant message: "
                                    + block.getClass().getSimpleName());
                }
            }
        }
    }

    private static void mapReasoningReplay(
            Msg msg, String encryptedContent, List<ResponseInputItem> items) {
        ResponseReasoningItem.Builder builder =
                ResponseReasoningItem.builder()
                        .id((JsonField) JsonMissing.of())
                        .encryptedContent(encryptedContent);

        ThinkingBlock thinkingBlock = msg.getFirstContentBlock(ThinkingBlock.class);
        String summary = thinkingBlock != null ? thinkingBlock.getThinking() : null;

        // SDK requires the summary field; set empty list when no summary text
        if (summary != null && !summary.isEmpty()) {
            builder.summary(List.of(ResponseReasoningItem.Summary.builder().text(summary).build()));
        } else {
            builder.summary(List.of());
        }
        items.add(ResponseInputItem.ofReasoning(builder.build()));
    }

    private static void mapToolUseBlock(ToolUseBlock tb, List<ResponseInputItem> items) {
        Objects.requireNonNull(tb.getId(), "ToolUseBlock.id must not be null for history replay");
        Objects.requireNonNull(
                tb.getName(), "ToolUseBlock.name must not be null for history replay");

        String arguments = JsonUtils.resolveToolCallArgsJson(tb);

        items.add(
                ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(tb.getId())
                                .name(tb.getName())
                                .arguments(arguments)
                                .build()));
    }

    static void mapToolMessage(Msg msg, List<ResponseInputItem> items) {
        List<?> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            throw new OpenAIOfficialModelException(
                    "Tool message has no content blocks; at least one"
                            + " ToolResultBlock is required for history replay.");
        }
        for (Object block : blocks) {
            if (block instanceof ToolResultBlock trb) {
                mapToolResultBlock(trb, items);
            } else {
                throw new OpenAIOfficialModelException(
                        "Unsupported content block in tool message: "
                                + block.getClass().getSimpleName());
            }
        }
    }

    private static void mapToolResultBlock(ToolResultBlock trb, List<ResponseInputItem> items) {
        Objects.requireNonNull(
                trb.getId(), "ToolResultBlock.id must not be null for history replay");

        List<ContentBlock> outputBlocks = trb.getOutput();

        // When all output blocks are text, use the simpler string form;
        // when any non-text block is present (image/data), use the list form
        // to preserve mixed content order.
        boolean hasNonText = false;
        for (ContentBlock block : outputBlocks) {
            if (!(block instanceof TextBlock)) {
                hasNonText = true;
                break;
            }
        }

        ResponseInputItem.FunctionCallOutput.Builder fcoBuilder =
                ResponseInputItem.FunctionCallOutput.builder().callId(trb.getId());
        if (hasNonText) {
            fcoBuilder.outputOfResponseFunctionCallOutputItemList(
                    mapToolResultOutputItems(outputBlocks));
        } else {
            fcoBuilder.output(
                    ResponseInputItem.FunctionCallOutput.Output.ofString(
                            joinOutputText(outputBlocks)));
        }
        items.add(ResponseInputItem.ofFunctionCallOutput(fcoBuilder.build()));
    }

    /**
     * Maps tool result output blocks to a list of {@link ResponseFunctionCallOutputItem},
     * preserving the original block order for mixed text/image content.
     */
    private static List<ResponseFunctionCallOutputItem> mapToolResultOutputItems(
            List<ContentBlock> blocks) {
        List<ResponseFunctionCallOutputItem> result = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                result.add(
                        ResponseFunctionCallOutputItem.ofInputText(
                                ResponseInputTextContent.builder().text(tb.getText()).build()));
            } else if (block instanceof ImageBlock ib) {
                result.add(buildImageOutputItem(resolveImageUrl(ib.getSource())));
            } else if (block instanceof DataBlock db) {
                result.add(buildImageOutputItem(resolveDataBlockImageUrl(db)));
            } else {
                throw new OpenAIOfficialModelException(
                        "Unsupported output block in ToolResultBlock: "
                                + block.getClass().getSimpleName());
            }
        }
        return result;
    }

    /**
     * Builds a {@link ResponseFunctionCallOutputItem} for an image URL.
     *
     * @param imageUrl the resolved image URL or data URI
     * @return an input_image variant of {@link ResponseFunctionCallOutputItem}
     */
    private static ResponseFunctionCallOutputItem buildImageOutputItem(String imageUrl) {
        return ResponseFunctionCallOutputItem.ofInputImage(
                ResponseInputImageContent.builder()
                        .imageUrl(imageUrl)
                        .detail(ResponseInputImageContent.Detail.of("auto"))
                        .build());
    }

    /**
     * Joins all {@link TextBlock} text in the given list with newline separators.
     *
     * @param blocks the output blocks (guaranteed non-null by {@link ToolResultBlock})
     * @return the joined text, or empty string if no text blocks are present
     */
    private static String joinOutputText(List<ContentBlock> blocks) {
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    // ── Tool definition mapping ──────────────────────────────────

    private static List<Tool> mapTools(List<ToolSchema> tools, Boolean strictTools) {
        List<Tool> result = new ArrayList<>();
        for (ToolSchema schema : tools) {
            result.add(Tool.ofFunction(mapFunctionTool(schema, strictTools)));
        }
        return result;
    }

    private static FunctionTool mapFunctionTool(ToolSchema schema, Boolean strictTools) {
        // Resolve strict to a boolean upfront (tool-level > builder-level > false),
        boolean effectiveStrict =
                schema.getStrict() != null ? schema.getStrict() : Boolean.TRUE.equals(strictTools);

        FunctionTool.Builder builder =
                FunctionTool.builder()
                        .name(schema.getName())
                        .description(schema.getDescription())
                        .strict(effectiveStrict);

        Map<String, Object> parameters = schema.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            builder.parameters(buildParameters(parameters));
        } else {
            builder.parameters(buildParameters(emptyToolParameters(effectiveStrict)));
        }

        if (schema.getOutputSchema() != null && !schema.getOutputSchema().isEmpty()) {
            builder.outputSchema(buildOutputSchema(schema.getOutputSchema()));
        }

        return builder.build();
    }

    private static FunctionTool.Parameters buildParameters(Map<String, Object> schema) {
        FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private static FunctionTool.OutputSchema buildOutputSchema(Map<String, Object> schema) {
        FunctionTool.OutputSchema.Builder builder = FunctionTool.OutputSchema.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private static ResponseFormatTextJsonSchemaConfig.Schema buildSchema(
            Map<String, Object> schema) {
        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    /**
     * Builds an empty parameters schema for tools without explicit parameters.
     *
     * <p>When {@code strict} is true, includes {@code additionalProperties: false} and
     * {@code required: []} per the OpenAI structured outputs spec. When false, omits
     * {@code additionalProperties}.
     */
    private static Map<String, Object> emptyToolParameters(boolean strict) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        if (strict) {
            schema.put("additionalProperties", false);
        }
        return schema;
    }

    // ── ToolChoice mapping ───────────────────────────────────────

    private static void mapToolChoice(ResponseCreateParams.Builder builder, ToolChoice choice) {
        if (choice instanceof ToolChoice.Auto) {
            builder.toolChoice(ToolChoiceOptions.AUTO);
        } else if (choice instanceof ToolChoice.None) {
            builder.toolChoice(ToolChoiceOptions.NONE);
        } else if (choice instanceof ToolChoice.Required) {
            builder.toolChoice(ToolChoiceOptions.REQUIRED);
        } else if (choice instanceof ToolChoice.Specific specific) {
            builder.toolChoice(ToolChoiceFunction.builder().name(specific.toolName()).build());
        } else {
            throw new OpenAIOfficialModelException(
                    "Unsupported ToolChoice type: " + choice.getClass().getSimpleName());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    static String collectText(Msg msg) {
        List<?> blocks = msg.getContent();
        if (blocks == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof TextBlock tb) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(tb.getText());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    static String resolveImageUrl(Source source) {
        if (source instanceof URLSource urlSource) {
            return urlSource.getUrl();
        }
        if (source instanceof Base64Source base64) {
            return "data:" + base64.getMediaType() + ";base64," + base64.getData();
        }
        throw new OpenAIOfficialModelException(
                "Unsupported image source type: " + source.getClass().getSimpleName());
    }

    static String resolveDataBlockImageUrl(DataBlock db) {
        Source source = db.getSource();
        if (source instanceof URLSource urlSource) {
            return urlSource.getUrl();
        }
        if (source instanceof Base64Source base64) {
            String mediaType = base64.getMediaType();
            if (mediaType == null || !mediaType.startsWith("image/")) {
                throw new OpenAIOfficialModelException(
                        "Non-image DataBlock is not supported by the"
                                + " openai-official provider.");
            }
            return "data:" + mediaType + ";base64," + base64.getData();
        }
        throw new OpenAIOfficialModelException(
                "Unsupported DataBlock source type: " + source.getClass().getSimpleName());
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
