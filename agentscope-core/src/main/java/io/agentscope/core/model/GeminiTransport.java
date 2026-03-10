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
import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.util.JsonCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * HTTP transport for Gemini API unary and streaming responses.
 *
 * <p>This class extracts network I/O concerns from {@link GeminiChatModel}
 * without changing response handling behavior.
 */
final class GeminiTransport {

    private static final Logger log = LoggerFactory.getLogger(GeminiTransport.class);

    private final OkHttpClient httpClient;
    private final JsonCodec jsonCodec;
    private final Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter;

    GeminiTransport(
            OkHttpClient httpClient,
            JsonCodec jsonCodec,
            Formatter<GeminiContent, GeminiResponse, GeminiRequest> formatter) {
        this.httpClient = httpClient;
        this.jsonCodec = jsonCodec;
        this.formatter = formatter;
    }

    Flux<ChatResponse> handleUnaryResponse(Request request, Instant startTime) {
        try {
            try (Response response = httpClient.newCall(request).execute();
                    ResponseBody responseBody = response.body()) {
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

    Flux<ChatResponse> handleStreamResponse(Request request, Instant startTime) {
        return Flux.create(
                sink -> {
                    // Use try-with-resources to manage Response and response body stream.
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

                        // Reading the stream.
                        try (BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                responseBody.byteStream(),
                                                StandardCharsets.UTF_8))) {

                            String line;
                            while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String json =
                                            line.substring(6).trim(); // Remove "data: " prefix.
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
}
