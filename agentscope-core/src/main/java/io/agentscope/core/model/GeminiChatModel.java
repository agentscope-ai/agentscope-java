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
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig.GeminiThinkingConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Gemini Chat Model implementation using the official Google GenAI Java SDK.
 *
 * <p>
 * This implementation provides complete integration with Gemini's Content
 * Generation API,
 * including tool calling and multi-agent conversation support.
 *
 * <p>
 * <b>Supported Features:</b>
 * <ul>
 * <li>Text generation with streaming and non-streaming modes</li>
 * <li>Tool/function calling support</li>
 * <li>Multi-agent conversation with history merging</li>
 * <li>Vision capabilities (images, audio, video)</li>
 * <li>Thinking mode (extended reasoning)</li>
 * </ul>
 */
public class GeminiChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);
    private static final String DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final String accessToken;
    private final String modelName;
    private final boolean streamEnabled;
    private final GenerateOptions defaultOptions;
    private final Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
    private final OkHttpClient httpClient;
    private final JsonCodec jsonCodec;

    /**
     * Creates a new Gemini chat model instance.
     *
     * @param baseUrl        the base URL for the API (optional)
     * @param apiKey         the API key for Gemini API (optional if accessToken
     *                       provided)
     * @param accessToken    the access token for Vertex AI (optional)
     * @param modelName      the model name (e.g., "gemini-2.0-flash")
     * @param streamEnabled  whether streaming should be enabled
     * @param defaultOptions default generation options
     * @param formatter      the message formatter to use
     * @param timeout        read/connect timeout in seconds (default: 60)
     * @param client         optional custom OkHttpClient
     */
    public GeminiChatModel(
            String baseUrl,
            String apiKey,
            String accessToken,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter,
            Long timeout,
            OkHttpClient client) {
        if (apiKey == null && accessToken == null) {
            throw new IllegalArgumentException("Either API Key or Access Token must be provided");
        }
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey = apiKey;
        this.accessToken = accessToken;
        this.modelName = Objects.requireNonNull(modelName, "Model name is required");
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new GeminiChatFormatter();

        if (client != null) {
            this.httpClient = client;
        } else {
            long timeoutVal = timeout != null ? timeout : 60L;
            this.httpClient =
                    new OkHttpClient.Builder()
                            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                            .connectTimeout(timeoutVal, TimeUnit.SECONDS)
                            .readTimeout(timeoutVal, TimeUnit.SECONDS)
                            .writeTimeout(timeoutVal, TimeUnit.SECONDS)
                            .build();
        }

        this.jsonCodec = JsonUtils.getJsonCodec();
    }

    /**
     * Stream chat completion responses from Gemini's API.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools    Optional list of tool schemas
     * @param options  Optional generation options
     * @return Flux stream of chat responses
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Gemini stream: model={}, messages={}, tools_present={}, streaming={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty(),
                streamEnabled);

        return Flux.defer(
                        () -> {
                            try {
                                // 1. Prepare Request DTO
                                GeminiRequest requestDto = new GeminiRequest();

                                // Format messages
                                List<GeminiContent> contents = formatter.format(messages);
                                requestDto.setContents(contents);

                                // Apply system instruction if formatter supports it
                                if (formatter instanceof GeminiChatFormatter chatFormatter) {
                                    chatFormatter.applySystemInstruction(requestDto, messages);
                                } else if (formatter
                                        instanceof GeminiMultiAgentFormatter multiAgentFormatter) {
                                    multiAgentFormatter.applySystemInstruction(
                                            requestDto, messages);
                                }

                                // Apply options, tools, tool choice
                                formatter.applyOptions(requestDto, options, defaultOptions);

                                // Compatibility fix for Gemini 3 models
                                // Disable thinking mode when tools are present to avoid
                                // MALFORMED_FUNCTION_CALL
                                if (modelName.toLowerCase().contains("gemini-3")) {
                                    if (tools != null && !tools.isEmpty()) {
                                        // When tools are present, ensure genConfig exists and
                                        // completely disable thinking mode
                                        GeminiGenerationConfig genConfig =
                                                requestDto.getGenerationConfig();
                                        if (genConfig == null) {
                                            genConfig = new GeminiGenerationConfig();
                                            requestDto.setGenerationConfig(genConfig);
                                        }
                                        // The combination of extended reasoning and tool calls
                                        // causes MALFORMED_FUNCTION_CALL API errors in Gemini 3
                                        log.debug(
                                                "Disabling thinking mode for Gemini 3 model when"
                                                        + " tools are present");
                                        GeminiThinkingConfig thinkingConfig =
                                                new GeminiThinkingConfig();
                                        thinkingConfig.setIncludeThoughts(false);
                                        genConfig.setThinkingConfig(thinkingConfig);
                                    } else {
                                        // For non-tool requests, adjust thinking config
                                        GeminiGenerationConfig genConfig =
                                                requestDto.getGenerationConfig();
                                        if (genConfig != null) {
                                            GeminiThinkingConfig thinkingConfig =
                                                    genConfig.getThinkingConfig();
                                            if (thinkingConfig != null) {
                                                if (thinkingConfig.getThinkingBudget() != null) {
                                                    log.debug(
                                                            "Removing thinkingBudget for Gemini 3"
                                                                    + " model compatibility");
                                                    thinkingConfig.setThinkingBudget(null);
                                                }
                                                thinkingConfig.setIncludeThoughts(true);
                                            }
                                        }
                                    }
                                }

                                if (tools != null && !tools.isEmpty()) {
                                    formatter.applyTools(requestDto, tools);
                                    if (options != null && options.getToolChoice() != null) {
                                        formatter.applyToolChoice(
                                                requestDto, options.getToolChoice());
                                    }
                                }

                                // 2. Serialize Request
                                String requestJson = jsonCodec.toJson(requestDto);
                                log.trace("Gemini Request JSON: {}", requestJson);
                                log.debug(
                                        "Gemini request: model={}, system_instruction={},"
                                                + " contents_count={}",
                                        modelName,
                                        requestDto.getSystemInstruction() != null,
                                        requestDto.getContents() != null
                                                ? requestDto.getContents().size()
                                                : 0);

                                // Debug: Log when tools are present
                                if (tools != null && !tools.isEmpty()) {
                                    log.debug(
                                            "Gemini request with {} tools for model: {}",
                                            tools.size(),
                                            modelName);
                                    if (requestDto.getTools() != null) {
                                        log.debug(
                                                "Request tools count: {}",
                                                requestDto.getTools().size());
                                    } else {
                                        log.warn("Tools were provided but request.tools is null!");
                                    }
                                }

                                // 3. Build HTTP Request
                                String endpoint =
                                        streamEnabled
                                                ? ":streamGenerateContent"
                                                : ":generateContent";
                                String url = this.baseUrl + modelName + endpoint;

                                if (streamEnabled) {
                                    url += "?alt=sse";
                                }

                                Request.Builder requestBuilder =
                                        new Request.Builder()
                                                .url(url)
                                                .post(RequestBody.create(requestJson, JSON));

                                if (accessToken != null) {
                                    requestBuilder.addHeader(
                                            "Authorization", "Bearer " + accessToken);
                                } else if (apiKey != null) {
                                    requestBuilder.addHeader("x-goog-api-key", apiKey);
                                }

                                Request httpRequest = requestBuilder.build();

                                // 4. Send Request and Handle Response
                                Flux<ChatResponse> responseFlux;
                                if (streamEnabled) {
                                    responseFlux = handleStreamResponse(httpRequest, startTime);
                                } else {
                                    responseFlux = handleUnaryResponse(httpRequest, startTime);
                                }

                                // 5. Add fallback for structured output (JSON text -> Tool Call)
                                return responseFlux.map(
                                        response ->
                                                ensureMeaningfulContent(
                                                        ensureStructuredOutputMetadata(
                                                                fixStructuredOutputResponse(
                                                                        response, options,
                                                                        tools))));

                            } catch (Exception e) {
                                log.error(
                                        "Failed to prepare Gemini request: {}", e.getMessage(), e);
                                return Flux.error(
                                        new ModelException(
                                                "Failed to prepare Gemini request: "
                                                        + e.getMessage(),
                                                e));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(
                        Retry.backoff(3, Duration.ofSeconds(1))
                                .filter(
                                        throwable -> {
                                            // Retry on HTTP errors (429, 5xx)
                                            if (throwable instanceof GeminiApiException) {
                                                int code =
                                                        ((GeminiApiException) throwable)
                                                                .getStatusCode();
                                                return code == 429 || (code >= 500 && code < 600);
                                            }

                                            // Retry on empty content errors for Gemini 3 models
                                            // This covers MALFORMED_FUNCTION_CALL, empty video
                                            // responses, etc.
                                            if (throwable instanceof ModelException
                                                    && modelName
                                                            .toLowerCase()
                                                            .contains("gemini-3")) {
                                                String errorMsg = throwable.getMessage();
                                                if (errorMsg != null
                                                        && errorMsg.contains("empty content")) {
                                                    log.warn(
                                                            "Detected Gemini 3 empty content error,"
                                                                    + " retrying...");
                                                    return true;
                                                }
                                            }
                                            return false;
                                        })
                                .doBeforeRetry(
                                        retrySignal ->
                                                log.debug(
                                                        "Retrying Gemini request (attempt {}):"
                                                                + " {}",
                                                        retrySignal.totalRetries() + 1,
                                                        retrySignal.failure().getMessage()))
                                .onRetryExhaustedThrow(
                                        (retryBackoffSpec, retrySignal) ->
                                                new ModelException(
                                                        "Gemini request failed after "
                                                                + retrySignal.totalRetries()
                                                                + " retries: "
                                                                + retrySignal
                                                                        .failure()
                                                                        .getMessage(),
                                                        retrySignal.failure())));
    }

    private Flux<ChatResponse> handleUnaryResponse(Request request, Instant startTime) {
        try {
            Response response = httpClient.newCall(request).execute();
            try (ResponseBody responseBody = response.body()) {
                String bodyString = responseBody != null ? responseBody.string() : null;
                if (!response.isSuccessful() || bodyString == null) {
                    String errorBody = bodyString != null ? bodyString : "null";
                    throw new GeminiApiException(response.code(), errorBody);
                }

                GeminiResponse geminiResponse =
                        jsonCodec.fromJson(bodyString, GeminiResponse.class);
                ChatResponse chatResponse = formatter.parseResponse(geminiResponse, startTime);
                return Flux.just(chatResponse);
            }
        } catch (IOException e) {
            return Flux.error(new ModelException("Gemini network error: " + e.getMessage(), e));
        }
    }

    private Flux<ChatResponse> handleStreamResponse(Request request, Instant startTime) {
        return Flux.create(
                sink -> {
                    // Use try-with-resources to manage Response and response body stream
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            try (ResponseBody body = response.body()) {
                                String error = body != null ? body.string() : "Unknown error";
                                sink.error(new GeminiApiException(response.code(), error));
                            }
                            return;
                        }

                        ResponseBody responseBody = response.body();
                        if (responseBody == null) {
                            sink.error(new IOException("Empty response body"));
                            return;
                        }

                        // Reading the stream
                        try (BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                responseBody.byteStream(),
                                                StandardCharsets.UTF_8))) {

                            String line;
                            while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String json =
                                            line.substring(6).trim(); // Remove "data: " prefix
                                    if (!json.isEmpty()) {
                                        try {
                                            GeminiResponse geminiResponse =
                                                    jsonCodec.fromJson(json, GeminiResponse.class);
                                            ChatResponse chatResponse =
                                                    formatter.parseResponse(
                                                            geminiResponse, startTime);
                                            sink.next(chatResponse);
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Failed to parse Gemini stream chunk: {}",
                                                    e.getMessage());
                                        }
                                    }
                                }
                            }
                        }

                        if (!sink.isCancelled()) {
                            sink.complete();
                        }

                    } catch (Exception e) {
                        sink.error(new ModelException("Gemini stream error: " + e.getMessage(), e));
                    }
                });
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Close the HTTP client resources if needed.
     */
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    /**
     * Fixes Gemini structured output responses when tools are expected.
     *
     * <p>
     * Gemini may return JSON text instead of a tool call, or omit required fields.
     * This method normalizes those responses into a ToolUseBlock for structured
     * output.
     */
    private ChatResponse fixStructuredOutputResponse(
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

        boolean toolCalled =
                blocks.stream()
                        .anyMatch(
                                b ->
                                        b instanceof ToolUseBlock
                                                && targetToolName.equals(
                                                        ((ToolUseBlock) b).getName()));

        if (toolCalled) {
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

        if (!looksLikeJson) {
            return response;
        }

        try {
            log.info(
                    "Attempting to fix Gemini response: converting text to tool call '{}'",
                    targetToolName);

            Object parsed = JsonUtils.getJsonCodec().fromJson(textContent, Object.class);
            Map<String, Object> inputMap;

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
                    Map<String, Object> properties =
                            (Map<String, Object>) parameters.get("properties");
                    if (properties == null) {
                        break;
                    }

                    boolean usesResponseWrapper =
                            properties.containsKey("response")
                                    && (properties.size() == 1
                                            || isRequired(parameters, "response"));
                    if (usesResponseWrapper) {
                        Object responseValue = inputMap.get("response");
                        boolean hasOtherKeys =
                                inputMap.keySet().stream().anyMatch(k -> !"response".equals(k));
                        if (!inputMap.containsKey("response")
                                || (responseValue == null && hasOtherKeys)) {
                            Map<String, Object> inner = new HashMap<>(inputMap);
                            inner.remove("response");
                            Map<String, Object> wrapped = new HashMap<>();
                            wrapped.put("response", inner);
                            inputMap = wrapped;
                        }
                    }

                    for (String key : properties.keySet()) {
                        if (!inputMap.containsKey(key)) {
                            inputMap.put(key, null);
                            log.debug("Added missing field '{}' as null", key);
                        }
                    }

                    if (usesResponseWrapper && properties.get("response") instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseSchema =
                                (Map<String, Object>) properties.get("response");
                        Object responsePropsObj = responseSchema.get("properties");
                        if (responsePropsObj instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> responseProps =
                                    (Map<String, Object>) responsePropsObj;
                            Map<String, Object> responseMap;
                            Object responseValue = inputMap.get("response");
                            if (responseValue instanceof Map<?, ?> responseValueMap) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> typedResponse =
                                        (Map<String, Object>) responseValueMap;
                                responseMap = new HashMap<>(typedResponse);
                            } else {
                                responseMap = new HashMap<>();
                            }

                            for (String key : responseProps.keySet()) {
                                if (!responseMap.containsKey(key)) {
                                    responseMap.put(key, null);
                                    log.debug("Added missing response field '{}' as null", key);
                                }
                            }

                            inputMap.put("response", responseMap);
                        }
                    }
                    break;
                }
            }

            String callId =
                    "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(callId)
                            .name(targetToolName)
                            .input(inputMap)
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

    private ChatResponse ensureStructuredOutputMetadata(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return response;
        }
        if (response.getMetadata() != null
                && response.getMetadata().containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT)) {
            return response;
        }

        Object structuredOutput = null;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock toolUse
                    && StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                            toolUse.getName())) {
                Map<String, Object> input = toolUse.getInput();
                if (input != null && !input.isEmpty()) {
                    Object responseValue = input.get("response");
                    structuredOutput = responseValue != null ? responseValue : input;
                }
                break;
            }
        }

        if (structuredOutput == null) {
            return response;
        }

        Map<String, Object> metadata =
                new HashMap<>(response.getMetadata() != null ? response.getMetadata() : Map.of());
        metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredOutput);

        return ChatResponse.builder()
                .id(response.getId())
                .content(response.getContent())
                .usage(response.getUsage())
                .finishReason(response.getFinishReason())
                .metadata(metadata)
                .build();
    }

    private ChatResponse ensureMeaningfulContent(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return response;
        }

        boolean hasText =
                response.getContent().stream()
                        .anyMatch(
                                block ->
                                        (block instanceof TextBlock textBlock
                                                        && textBlock.getText() != null
                                                        && !textBlock.getText().isBlank())
                                                || (block instanceof ThinkingBlock thinkingBlock
                                                        && thinkingBlock.getThinking() != null
                                                        && !thinkingBlock.getThinking().isBlank())
                                                || block instanceof ToolUseBlock);

        if (hasText) {
            return response;
        }

        // Only add fallback text for actual error finish reasons
        // STOP, null, or empty finish reason can occur in streaming and should be
        // ignored
        String finishReason = response.getFinishReason();
        if (finishReason == null
                || finishReason.isEmpty()
                || finishReason.equals("STOP")
                || finishReason.equals("MAX_TOKENS")
                || finishReason.equals("END_TURN")) {
            // Normal completion or streaming chunk - don't add fallback text or retry
            return response;
        }

        // For Gemini 3 models, throw exception on problematic finish reasons
        // to trigger retry logic (workaround for API instability)
        if (modelName.toLowerCase().contains("gemini-3")) {
            // Retry on MALFORMED_FUNCTION_CALL (mainly for tool calls)
            if (finishReason.equals("MALFORMED_FUNCTION_CALL")) {
                log.warn("Gemini 3 model returned MALFORMED_FUNCTION_CALL, will trigger retry");
                throw new ModelException(
                        "Gemini returned empty content (finishReason: MALFORMED_FUNCTION_CALL)");
            }

            // Also retry on other error finish reasons that result in empty content
            // This handles cases like multi-round conversations returning empty responses
            if (finishReason.equals("SAFETY")
                    || finishReason.equals("RECITATION")
                    || finishReason.equals("OTHER")) {
                log.warn(
                        "Gemini 3 model returned empty content with finishReason: {}, will"
                                + " trigger retry",
                        finishReason);
                throw new ModelException(
                        "Gemini returned empty content (finishReason: " + finishReason + ")");
            }
        }

        // For other error finish reasons, add fallback text
        String fallback = "Gemini returned empty content";
        if (!finishReason.isEmpty()) {
            fallback += " (finishReason: " + finishReason + ")";
        }

        List<ContentBlock> newBlocks = new ArrayList<>(response.getContent());
        newBlocks.add(TextBlock.builder().text(fallback).build());

        return ChatResponse.builder()
                .id(response.getId())
                .content(newBlocks)
                .usage(response.getUsage())
                .finishReason(response.getFinishReason())
                .metadata(response.getMetadata())
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
     * Creates a structured output response with empty/null values when Gemini
     * returns no content.
     * This ensures that the structured output metadata key is populated even when
     * the model fails.
     */
    private ChatResponse createEmptyStructuredOutputResponse(
            ChatResponse response, String targetToolName, List<ToolSchema> tools) {

        Map<String, Object> inputMap = new HashMap<>();

        // Find the tool schema and populate all required fields with null
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
                                    responseMap.put(key, "unavailable");
                                }
                                inputMap.put("response", responseMap);
                            } else {
                                inputMap.put("response", new HashMap<String, Object>());
                            }
                        } else {
                            // Flat structure - populate all properties with "unavailable"
                            for (String key : properties.keySet()) {
                                inputMap.put(key, "unavailable");
                            }
                        }
                    }
                }
                break;
            }
        }

        // If we couldn't determine the schema structure, create a simple response
        // wrapper
        if (inputMap.isEmpty()) {
            inputMap.put("response", Map.of("error", "Model returned no content"));
        }

        String callId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        // Create metadata marking this as a synthetic tool call
        // The GeminiMessageConverter will convert synthetic tool calls to text
        // to avoid Gemini 3 validation issues with thought_signature
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("synthetic", true);
        metadata.put("empty_response", true);

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id(callId)
                        .name(targetToolName)
                        .input(inputMap)
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

    /**
     * Creates a new builder for GeminiChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GeminiChatModel instances.
     */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String accessToken;
        private String modelName = "gemini-2.5-flash";
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions;
        private Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
        private Long timeout;
        private OkHttpClient httpClient;

        private List<Protocol> protocols = Collections.singletonList(Protocol.HTTP_1_1);
        private String project;
        private String location;
        private Boolean vertexAI;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder streamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder formatter(
                Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder timeout(Long timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder httpClient(OkHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder protocols(List<Protocol> protocols) {
            this.protocols = protocols;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder vertexAI(Boolean vertexAI) {
            this.vertexAI = vertexAI;
            return this;
        }

        public GeminiChatModel build() {
            OkHttpClient client = this.httpClient;
            if (client == null) {
                long timeoutVal = this.timeout != null ? this.timeout : 60L;
                OkHttpClient.Builder clientBuilder =
                        new OkHttpClient.Builder()
                                .connectTimeout(timeoutVal, TimeUnit.SECONDS)
                                .readTimeout(timeoutVal, TimeUnit.SECONDS)
                                .writeTimeout(timeoutVal, TimeUnit.SECONDS);

                if (this.protocols != null) {
                    clientBuilder.protocols(this.protocols);
                }
                client = clientBuilder.build();
            }

            // Construct Vertex AI Base URL if needed
            String finalBaseUrl = this.baseUrl;
            if (finalBaseUrl == null
                    && (Boolean.TRUE.equals(this.vertexAI)
                            || (this.project != null && !this.project.isEmpty()))) {
                String loc =
                        this.location != null && !this.location.isEmpty()
                                ? this.location
                                : "us-central1";
                if (this.project == null || this.project.isEmpty()) {
                    throw new IllegalArgumentException("Project ID is required for Vertex AI");
                }
                finalBaseUrl =
                        String.format(
                                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/",
                                loc, this.project, loc);
            }

            return new GeminiChatModel(
                    finalBaseUrl,
                    apiKey,
                    accessToken,
                    modelName,
                    streamEnabled,
                    defaultOptions,
                    formatter,
                    timeout,
                    client);
        }
    }

    /** Exception for Gemini API specific errors. */
    public static class GeminiApiException extends RuntimeException {
        private final int statusCode;
        private final String body;

        public GeminiApiException(int statusCode, String body) {
            super("Gemini API Error: " + statusCode + " - " + body);
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
