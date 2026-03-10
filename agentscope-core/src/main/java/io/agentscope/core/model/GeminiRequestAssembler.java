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

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;

/**
 * Assembles Gemini HTTP requests from model inputs.
 *
 * <p>This class extracts request DTO construction, compatibility policy application,
 * and HTTP request building from {@link GeminiChatModel} while preserving behavior.
 */
final class GeminiRequestAssembler {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final String modelName;
    private final boolean streamEnabled;
    private final String apiKey;
    private final String accessToken;
    private final GenerateOptions defaultOptions;
    private final Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;
    private final JsonCodec jsonCodec;
    private final GeminiThinkingPolicy thinkingPolicy;
    private final Logger log;

    GeminiRequestAssembler(
            String baseUrl,
            String modelName,
            boolean streamEnabled,
            String apiKey,
            String accessToken,
            GenerateOptions defaultOptions,
            Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter,
            JsonCodec jsonCodec,
            GeminiThinkingPolicy thinkingPolicy,
            Logger log) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.streamEnabled = streamEnabled;
        this.apiKey = apiKey;
        this.accessToken = accessToken;
        this.defaultOptions = defaultOptions;
        this.formatter = formatter;
        this.jsonCodec = jsonCodec;
        this.thinkingPolicy = thinkingPolicy;
        this.log = log;
    }

    PreparedRequest assemble(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        GeminiRequest requestDto = new GeminiRequest();

        // Format messages
        List<GeminiContent> contents = formatter.format(messages);
        contents = ensureConversationEndsWithUserRole(contents);
        requestDto.setContents(contents);

        // Apply system instruction if formatter supports it
        if (formatter instanceof GeminiChatFormatter chatFormatter) {
            chatFormatter.applySystemInstruction(requestDto, messages);
        } else if (formatter instanceof GeminiMultiAgentFormatter multiAgentFormatter) {
            multiAgentFormatter.applySystemInstruction(requestDto, messages);
        }

        // Apply options, tools, tool choice
        formatter.applyOptions(requestDto, options, defaultOptions);

        boolean isGemini3FlashStructuredOutput =
                thinkingPolicy.disableThinkingForGemini3FlashStructuredOutput(
                        modelName, requestDto, tools);
        thinkingPolicy.applyGemini3CompatibilityPolicy(
                modelName, requestDto, tools, isGemini3FlashStructuredOutput);

        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(requestDto, tools);
            if (options != null && options.getToolChoice() != null) {
                formatter.applyToolChoice(requestDto, options.getToolChoice());
            }
        }

        String requestJson = jsonCodec.toJson(requestDto);
        log.debug("Gemini Request JSON: {}", requestJson);
        log.debug(
                "Gemini request: model={}, system_instruction={}, contents_count={}",
                modelName,
                requestDto.getSystemInstruction() != null,
                requestDto.getContents() != null ? requestDto.getContents().size() : 0);

        if (tools != null && !tools.isEmpty()) {
            log.debug("Gemini request with {} tools for model: {}", tools.size(), modelName);
            if (requestDto.getTools() != null) {
                log.debug("Request tools count: {}", requestDto.getTools().size());
            } else {
                log.warn("Tools were provided but request.tools is null!");
            }
        }

        boolean forceUnaryForStructuredOutput =
                thinkingPolicy.applyForceUnaryForStructuredOutput(modelName, requestDto, tools);
        boolean streamForRequest = streamEnabled && !forceUnaryForStructuredOutput;

        String endpoint = streamForRequest ? ":streamGenerateContent" : ":generateContent";
        String url = this.baseUrl + modelName + endpoint;

        if (streamForRequest) {
            url += "?alt=sse";
        }

        Request.Builder requestBuilder =
                new Request.Builder().url(url).post(RequestBody.create(requestJson, JSON));

        if (accessToken != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
        } else if (apiKey != null) {
            requestBuilder.addHeader("x-goog-api-key", apiKey);
        }

        return new PreparedRequest(requestBuilder.build(), streamForRequest);
    }

    /**
     * Ensure the conversation ends with "user" role to prompt a response.
     */
    private List<GeminiContent> ensureConversationEndsWithUserRole(List<GeminiContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        GeminiContent lastContent = contents.get(contents.size() - 1);
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

    private boolean hasFunctionCall(GeminiContent content) {
        if (content == null || content.getParts() == null) {
            return false;
        }
        return content.getParts().stream().anyMatch(part -> part.getFunctionCall() != null);
    }

    static final class PreparedRequest {
        private final Request httpRequest;
        private final boolean streamForRequest;

        PreparedRequest(Request httpRequest, boolean streamForRequest) {
            this.httpRequest = httpRequest;
            this.streamForRequest = streamForRequest;
        }

        Request getHttpRequest() {
            return httpRequest;
        }

        boolean isStreamForRequest() {
            return streamForRequest;
        }
    }
}
