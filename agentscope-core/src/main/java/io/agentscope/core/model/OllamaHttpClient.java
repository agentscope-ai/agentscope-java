/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaEmbeddingResponse;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.TransportConstants;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * HTTP client for Ollama API.
 *
 * <p>This client handles communication with Ollama's chat and embedding APIs.
 * It provides both synchronous and asynchronous (streaming) request capabilities
 * with proper JSON serialization/deserialization and error handling.
 *
 * <p>Features:
 * <ul>
 *   <li>Synchronous and streaming request support</li>
 *   <li>NDJSON stream parsing for streaming responses</li>
 *   <li>Automatic JSON serialization/deserialization with snake_case naming</li>
 *   <li>Proper error handling with detailed exception information</li>
 *   <li>Configurable base URL and HTTP transport</li>
 * </ul>
 *
 * <p>Default base URL is http://localhost:11434, which is the standard Ollama server address.
 *
 */
public class OllamaHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaHttpClient.class);

    /** Default base URL for Ollama API. */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /** Chat API endpoint. */
    public static final String CHAT_ENDPOINT = "/api/chat";

    /** Embed API endpoint. */
    public static final String EMBED_ENDPOINT = "/api/embed";

    private final HttpTransport transport;
    private final String baseUrl;

    /**
     * Create a new OllamaHttpClient.
     *
     * @param transport the HTTP transport to use
     * @param baseUrl the base URL (null for default)
     */
    public OllamaHttpClient(HttpTransport transport, String baseUrl) {
        this.transport = transport;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
    }

    /**
     * Make a synchronous Chat API call.
     *
     * @param request the Ollama request
     * @return the Ollama response
     */
    public OllamaResponse chat(OllamaRequest request) {
        return call(CHAT_ENDPOINT, request);
    }

    /**
     * Make a synchronous Embed API call.
     *
     * @param request the Embed request
     * @return the Embed response
     */
    public OllamaEmbeddingResponse embed(OllamaEmbeddingRequest request) {
        return call(EMBED_ENDPOINT, request, OllamaEmbeddingResponse.class);
    }

    /**
     * Make a synchronous API call to specific endpoint.
     *
     * @param endpoint the API endpoint
     * @param request the request object
     * @return the Ollama response
     */
    public OllamaResponse call(String endpoint, Object request) {
        return call(endpoint, request, OllamaResponse.class);
    }

    /**
     * Make a synchronous API call to specific endpoint with custom response type.
     *
     * @param endpoint the API endpoint
     * @param request the request object
     * @param responseType the response class
     * @param <T> the response type
     * @return the response object
     */
    public <T> T call(String endpoint, Object request, Class<T> responseType) {
        String url = baseUrl + endpoint;

        final String requestBody;
        try {
            requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Ollama request to {}: {}", url, requestBody);
        } catch (JsonException e) {
            // Known Jackson checked exception -> wrap into OllamaHttpException
            throw new OllamaHttpException("Failed to serialize/deserialize request", e);
        } catch (RuntimeException e) {
            // Some serialization failures may manifest as RuntimeException; normalize for
            // callers/tests.
            throw new OllamaHttpException("JSON serialization error", e);
        }

        HttpRequest httpRequest =
                HttpRequest.builder()
                        .url(url)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body(requestBody)
                        .build();

        HttpResponse httpResponse;
        try {
            httpResponse = transport.execute(httpRequest);
        } catch (HttpTransportException e) {
            throw new OllamaHttpException("HTTP transport error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Catch runtime exceptions from transport and wrap them as OllamaHttpException
            throw new OllamaHttpException(
                    "Runtime error during transport execution: " + e.getMessage(), e);
        }

        if (!httpResponse.isSuccessful()) {
            throw new OllamaHttpException(
                    "Ollama API request failed with status " + httpResponse.getStatusCode(),
                    httpResponse.getStatusCode(),
                    httpResponse.getBody());
        }

        String responseBody = httpResponse.getBody();
        log.debug("Ollama response: {}", responseBody);

        try {
            return JsonUtils.getJsonCodec().fromJson(responseBody, responseType);
        } catch (JsonException e) {
            throw new OllamaHttpException("Failed to serialize/deserialize response", e);
        } catch (RuntimeException e) {
            // Some deserialization failures may manifest as RuntimeException
            throw new OllamaHttpException("JSON deserialization error", e);
        }
    }

    /**
     * Make a streaming Chat API call.
     *
     * @param request the Ollama request
     * @return a Flux of Ollama responses
     */
    public Flux<OllamaResponse> stream(OllamaRequest request) {
        String url = baseUrl + CHAT_ENDPOINT;

        try {
            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Ollama streaming request to {}: {}", url, requestBody);

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            // Enable NDJSON parsing in OkHttpTransport
            headers.put(
                    TransportConstants.STREAM_FORMAT_HEADER,
                    TransportConstants.STREAM_FORMAT_NDJSON);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(headers)
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .map(
                            data -> {
                                try {
                                    OllamaResponse response =
                                            JsonUtils.getJsonCodec()
                                                    .fromJson(data, OllamaResponse.class);
                                    if (response.getError() != null) {
                                        log.error(
                                                "Ollama streaming error: {}", response.getError());
                                        throw new OllamaHttpException(
                                                "Ollama streaming error: " + response.getError());
                                    }
                                    return response;
                                } catch (JsonException e) {
                                    log.warn(
                                            "Failed to parse Ollama NDJSON data: {}. Error: {}",
                                            data,
                                            e.getMessage());
                                    return null;
                                }
                            })
                    .filter(response -> response != null);

        } catch (JsonException e) {
            return Flux.error(new OllamaHttpException("Failed to serialize request", e));
        }
    }

    /**
     * Get the base URL.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Close the transport.
     */
    public void close() {
        transport.close();
    }

    /**
     * Create a new builder for OllamaHttpClient.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OllamaHttpClient.
     */
    public static class Builder {
        private HttpTransport transport;
        private String baseUrl;

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public OllamaHttpClient build() {
            if (transport == null) {
                transport = HttpTransportFactory.getDefault();
            }
            return new OllamaHttpClient(transport, baseUrl);
        }
    }

    /**
     * Exception thrown when Ollama HTTP operations fail.
     */
    public static class OllamaHttpException extends RuntimeException {
        private final Integer statusCode;
        private final String responseBody;

        public OllamaHttpException(String message) {
            super(message);
            this.statusCode = null;
            this.responseBody = null;
        }

        public OllamaHttpException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.responseBody = null;
        }

        public OllamaHttpException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public OllamaHttpException(String message, String errorCode, String responseBody) {
            super(message);
            this.statusCode = null;
            this.responseBody = responseBody;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
