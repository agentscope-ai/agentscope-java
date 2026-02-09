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
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
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
import java.util.Set;
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

    // Retry configuration aligned with Google GenAI SDK
    // See: java-genai/src/main/java/com/google/genai/RetryInterceptor.java
    private static final int RETRY_MAX_ATTEMPTS = 5;
    private static final long RETRY_MAX_DELAY_SECONDS = 60;
    private static final double RETRY_JITTER_FACTOR = 0.5;
    private static final Set<Integer> RETRYABLE_HTTP_STATUS_CODES =
            Set.of(
                    408, // Request Timeout
                    429, // Too Many Requests
                    500, // Internal Server Error
                    502, // Bad Gateway
                    503, // Service Unavailable
                    504 // Gateway Timeout
                    );

    // Expected finish reasons aligned with Google GenAI SDK
    // See: java-genai/src/main/java/com/google/genai/types/GenerateContentResponse.java
    private static final Set<String> EXPECTED_FINISH_REASONS =
            Set.of(
                    "FINISH_REASON_UNSPECIFIED",
                    "STOP",
                    "MAX_TOKENS",
                    "END_TURN" // AgentScope-specific for streaming compatibility
                    );

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

                                // Multi-agent fix: If conversation ends with "model" role,
                                // add a synthetic "user" message to prompt a response.
                                // This handles cases where other agents' ASSISTANT messages
                                // become "model" role and Gemini doesn't know to respond.
                                contents = ensureConversationEndsWithUserRole(contents);

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

                                // CRITICAL FIX: For Gemini 3 Flash + structured output, disable
                                // thinking immediately after applyOptions
                                // This must happen BEFORE the general Gemini 3 compatibility logic
                                // to prevent being overridden
                                boolean isGemini3FlashStructuredOutput = false;
                                if (modelName.toLowerCase().contains("gemini-3-flash")
                                        && tools != null) {
                                    for (ToolSchema tool : tools) {
                                        if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME
                                                .equals(tool.getName())) {
                                            isGemini3FlashStructuredOutput = true;
                                            GeminiGenerationConfig genConfig =
                                                    requestDto.getGenerationConfig();
                                            if (genConfig == null) {
                                                genConfig = new GeminiGenerationConfig();
                                                requestDto.setGenerationConfig(genConfig);
                                            }
                                            // CRITICAL: Set thinkingConfig to null to completely
                                            // remove it
                                            // Setting includeThoughts=false doesn't work - we must
                                            // remove the entire config
                                            genConfig.setThinkingConfig(null);
                                            break;
                                        }
                                    }
                                }

                                // Compatibility fix for Gemini 3 models
                                // Disable thinking mode when tools are present to avoid
                                // MALFORMED_FUNCTION_CALL
                                if (modelName.toLowerCase().contains("gemini-3")
                                        && !isGemini3FlashStructuredOutput) {

                                    // Check if there are non-structured-output tools
                                    boolean hasNonStructuredOutputTools = false;
                                    if (tools != null && !tools.isEmpty()) {
                                        log.info(
                                                "Tools present for Gemini 3, count: {}",
                                                tools.size());
                                        for (ToolSchema tool : tools) {
                                            log.info("Tool name: {}", tool.getName());
                                            if (!StructuredOutputCapableAgent
                                                    .STRUCTURED_OUTPUT_TOOL_NAME
                                                    .equals(tool.getName())) {
                                                hasNonStructuredOutputTools = true;
                                                log.info(
                                                        "Found non-structured-output tool: {}",
                                                        tool.getName());
                                                break;
                                            }
                                        }
                                    } else {
                                        log.info("No tools present for Gemini 3");
                                    }

                                    if (hasNonStructuredOutputTools) {
                                        // When non-structured-output tools are present, ensure
                                        // genConfig exists and
                                        // completely disable thinking mode
                                        GeminiGenerationConfig genConfig =
                                                requestDto.getGenerationConfig();
                                        if (genConfig == null) {
                                            genConfig = new GeminiGenerationConfig();
                                            requestDto.setGenerationConfig(genConfig);
                                        }
                                        // The combination of extended reasoning and tool calls
                                        // causes MALFORMED_FUNCTION_CALL API errors in Gemini 3
                                        log.info(
                                                "Disabling thinking mode for Gemini 3 model when"
                                                    + " non-structured-output tools are present");
                                        GeminiThinkingConfig thinkingConfig =
                                                new GeminiThinkingConfig();
                                        thinkingConfig.setIncludeThoughts(false);
                                        genConfig.setThinkingConfig(thinkingConfig);
                                    } else {
                                        // For structured output or non-tool requests, adjust
                                        // thinking config
                                        // BUT: Don't enable thinking for Gemini 3 Flash with
                                        // structured output
                                        // as it causes tool hallucination
                                        boolean isGemini3Flash =
                                                modelName.toLowerCase().contains("gemini-3-flash");
                                        boolean hasStructuredOutputTool = false;
                                        if (tools != null) {
                                            for (ToolSchema tool : tools) {
                                                if (StructuredOutputCapableAgent
                                                        .STRUCTURED_OUTPUT_TOOL_NAME
                                                        .equals(tool.getName())) {
                                                    hasStructuredOutputTool = true;
                                                    break;
                                                }
                                            }
                                        }

                                        if (isGemini3Flash && hasStructuredOutputTool) {
                                            log.info(
                                                    "Disabling thinking config for Gemini 3 Flash"
                                                            + " structured output to avoid tool"
                                                            + " hallucination");
                                            // CRITICAL: Actively disable thinking for Gemini 3
                                            // Flash structured output
                                            GeminiGenerationConfig genConfig =
                                                    requestDto.getGenerationConfig();
                                            if (genConfig == null) {
                                                genConfig = new GeminiGenerationConfig();
                                                requestDto.setGenerationConfig(genConfig);
                                            }
                                            GeminiThinkingConfig thinkingConfig =
                                                    new GeminiThinkingConfig();
                                            thinkingConfig.setIncludeThoughts(false);
                                            thinkingConfig.setThinkingBudget(null);
                                            genConfig.setThinkingConfig(thinkingConfig);
                                        } else {
                                            log.info(
                                                    "Adjusting thinking config for Gemini 3"
                                                            + " (structured output or no tools)");
                                            GeminiGenerationConfig genConfig =
                                                    requestDto.getGenerationConfig();
                                            log.info("Current genConfig: {}", genConfig);
                                            if (genConfig != null) {
                                                GeminiThinkingConfig thinkingConfig =
                                                        genConfig.getThinkingConfig();
                                                log.info(
                                                        "Current thinkingConfig: {}",
                                                        thinkingConfig);
                                                if (thinkingConfig != null) {
                                                    if (thinkingConfig.getThinkingBudget()
                                                            != null) {
                                                        log.info(
                                                                "Removing thinkingBudget for Gemini"
                                                                        + " 3 model compatibility");
                                                        thinkingConfig.setThinkingBudget(null);
                                                    }
                                                    thinkingConfig.setIncludeThoughts(true);
                                                    log.info(
                                                            "Set includeThoughts=true for Gemini"
                                                                    + " 3");
                                                } else {
                                                    log.warn(
                                                            "thinkingConfig is null, cannot enable"
                                                                    + " thinking mode");
                                                }
                                            } else {
                                                log.warn(
                                                        "genConfig is null, cannot adjust thinking"
                                                                + " config");
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
                                log.info(
                                        "Gemini Request JSON: {}",
                                        requestJson); // Changed to INFO for debugging
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
                                boolean forceUnaryForStructuredOutput = false;
                                if (tools != null) {
                                    for (ToolSchema tool : tools) {
                                        if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME
                                                .equals(tool.getName())) {
                                            forceUnaryForStructuredOutput = true;

                                            // CRITICAL FIX: Gemini 3 Flash has a known issue where
                                            // thinking mode
                                            // causes it to hallucinate tool names instead of using
                                            // generate_response
                                            if (modelName
                                                    .toLowerCase()
                                                    .contains("gemini-3-flash")) {
                                                GeminiGenerationConfig genConfig =
                                                        requestDto.getGenerationConfig();
                                                if (genConfig == null) {
                                                    genConfig = new GeminiGenerationConfig();
                                                    requestDto.setGenerationConfig(genConfig);
                                                }
                                                GeminiThinkingConfig thinkingConfig =
                                                        new GeminiThinkingConfig();
                                                thinkingConfig.setIncludeThoughts(false);
                                                genConfig.setThinkingConfig(thinkingConfig);
                                            }
                                            break;
                                        }
                                    }
                                }

                                boolean streamForRequest =
                                        streamEnabled && !forceUnaryForStructuredOutput;

                                String endpoint =
                                        streamForRequest
                                                ? ":streamGenerateContent"
                                                : ":generateContent";
                                String url = this.baseUrl + modelName + endpoint;

                                if (streamForRequest) {
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
                                if (streamForRequest) {
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
                        Retry.backoff(RETRY_MAX_ATTEMPTS, Duration.ofSeconds(1))
                                .maxBackoff(Duration.ofSeconds(RETRY_MAX_DELAY_SECONDS))
                                .jitter(RETRY_JITTER_FACTOR)
                                .filter(
                                        throwable -> {
                                            // Retry on retryable HTTP status codes
                                            // Aligned with Google GenAI SDK:
                                            // 408 (Request Timeout), 429 (Too Many Requests),
                                            // 500 (Internal Server Error), 502 (Bad Gateway),
                                            // 503 (Service Unavailable), 504 (Gateway Timeout)
                                            if (throwable instanceof GeminiApiException) {
                                                int code =
                                                        ((GeminiApiException) throwable)
                                                                .getStatusCode();
                                                boolean isRetryable =
                                                        RETRYABLE_HTTP_STATUS_CODES.contains(code);
                                                if (isRetryable) {
                                                    log.debug(
                                                            "Retryable HTTP status code: {}", code);
                                                }
                                                return isRetryable;
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
                                                        "Retrying Gemini request (attempt {}/{}):"
                                                                + " {}",
                                                        retrySignal.totalRetries() + 1,
                                                        RETRY_MAX_ATTEMPTS,
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
                log.info("Gemini Response JSON: {}", bodyString);
                log.info(
                        "Parsed GeminiResponse: candidates={}, promptFeedback={}",
                        geminiResponse.getCandidates() != null
                                ? geminiResponse.getCandidates().size()
                                : 0,
                        geminiResponse.getPromptFeedback());
                ChatResponse chatResponse = formatter.parseResponse(geminiResponse, startTime);
                log.info(
                        "Parsed ChatResponse: contentBlocks={}, metadata={}",
                        chatResponse.getContent() != null ? chatResponse.getContent().size() : 0,
                        chatResponse.getMetadata());
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

    private ChatResponse ensureStructuredOutputMetadata(ChatResponse response) {
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

    /**
     * Ensure the conversation ends with "user" role to prompt a response.
     *
     * <p>In multi-agent scenarios, other agents' ASSISTANT messages become "model" role.
     * If the conversation ends with "model" (without function_call), Gemini may not
     * understand it should generate a new response. This method adds a synthetic
     * "user" message to prompt continuation.
     *
     * @param contents List of Content objects
     * @return List with synthetic user message added if needed
     */
    private List<GeminiContent> ensureConversationEndsWithUserRole(List<GeminiContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        GeminiContent lastContent = contents.get(contents.size() - 1);

        // Only add synthetic message if:
        // 1. Last message is "model" role
        // 2. It doesn't contain function_call (which expects function_response, not text)
        // 3. The conversation has at least 2 messages (real conversation, not single message)
        if ("model".equals(lastContent.getRole())
                && !hasFunctionCall(lastContent)
                && contents.size() >= 2) {
            List<GeminiContent> result = new ArrayList<>(contents);
            GeminiPart part = new GeminiPart();
            part.setText("Please continue with your response.");
            GeminiContent syntheticUserContent = new GeminiContent("user", List.of(part));
            result.add(syntheticUserContent);
            log.debug("Added synthetic user message to prompt response after model message");
            return result;
        }

        return contents;
    }

    /**
     * Check if a Content contains function_call.
     */
    private boolean hasFunctionCall(GeminiContent content) {
        if (content == null || content.getParts() == null) {
            return false;
        }
        return content.getParts().stream().anyMatch(part -> part.getFunctionCall() != null);
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

        // Check finish reason against expected values (aligned with GenAI SDK pattern)
        // Expected finish reasons indicate normal completion: STOP, MAX_TOKENS, etc.
        // Unexpected finish reasons may indicate API issues that should be retried
        String finishReason = response.getFinishReason();
        if (finishReason == null
                || finishReason.isEmpty()
                || EXPECTED_FINISH_REASONS.contains(finishReason)) {
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

        // For other unexpected finish reasons, log a warning and add fallback text
        log.warn(
                "Gemini returned unexpected finishReason: {}. Expected one of: {}",
                finishReason,
                EXPECTED_FINISH_REASONS);
        String fallback = "Gemini returned empty content (finishReason: " + finishReason + ")";

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
     * Returns a type-appropriate default value based on JSON Schema type.
     *
     * <p>This method ensures that missing fields in structured output responses
     * are populated with meaningful defaults instead of null, which helps
     * maintain data integrity when the model returns incomplete JSON.
     *
     * @param schemaProperty The JSON Schema property definition (may contain "type")
     * @return A default value appropriate for the schema type:
     *         - "string" -> empty string ""
     *         - "number"/"integer" -> 0
     *         - "boolean" -> false
     *         - "array" -> empty list
     *         - "object" -> empty map
     *         - other/null -> empty string (conservative default for Gemini)
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
