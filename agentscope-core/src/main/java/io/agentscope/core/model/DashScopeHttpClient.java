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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * HTTP client for DashScope API.
 *
 * <p>This client handles communication with DashScope's text-generation and
 * multimodal-generation APIs using the native DashScope protocol.
 *
 * <p>Features:
 * <ul>
 *   <li>Automatic API endpoint routing based on model name</li>
 *   <li>Synchronous and streaming request support</li>
 *   <li>SSE stream parsing</li>
 *   <li>JSON serialization/deserialization</li>
 * </ul>
 *
 * <p>API endpoints:
 * <ul>
 *   <li>Text generation: /api/v1/services/aigc/text-generation/generation</li>
 *   <li>Multimodal generation: /api/v1/services/aigc/multimodal-generation/generation</li>
 * </ul>
 */
public class DashScopeHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeHttpClient.class);

    /** Default base URL for DashScope API. */
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    /** Text generation API endpoint. */
    public static final String TEXT_GENERATION_ENDPOINT =
            "/api/v1/services/aigc/text-generation/generation";

    /** Multimodal generation API endpoint. */
    public static final String MULTIMODAL_GENERATION_ENDPOINT =
            "/api/v1/services/aigc/multimodal-generation/generation";

    private final HttpTransport transport;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    /**
     * Create a new DashScopeHttpClient.
     *
     * @param transport the HTTP transport to use
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     */
    public DashScopeHttpClient(HttpTransport transport, String apiKey, String baseUrl) {
        this.transport = transport;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.objectMapper = createObjectMapper();
    }

    /**
     * Create a new DashScopeHttpClient with default transport from factory.
     *
     * <p>Uses {@link HttpTransportFactory#getDefault()} for the transport, which
     * provides automatic lifecycle management and cleanup on JVM shutdown.
     *
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     */
    public DashScopeHttpClient(String apiKey, String baseUrl) {
        this(HttpTransportFactory.getDefault(), apiKey, baseUrl);
    }

    /**
     * Create a new DashScopeHttpClient with default transport and base URL.
     *
     * @param apiKey the DashScope API key
     */
    public DashScopeHttpClient(String apiKey) {
        this(apiKey, null);
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Make a synchronous API call.
     *
     * @param request the DashScope request
     * @return the DashScope response
     * @throws DashScopeHttpException if the request fails
     */
    public DashScopeResponse call(DashScopeRequest request) {
        return call(request, null, null, null);
    }

    /**
     * Make a synchronous API call with additional HTTP parameters.
     *
     * @param request the DashScope request
     * @param additionalHeaders additional HTTP headers to merge (may be null)
     * @param additionalBodyParams additional body parameters to merge (may be null)
     * @param additionalQueryParams additional query parameters to append (may be null)
     * @return the DashScope response
     * @throws DashScopeHttpException if the request fails
     */
    public DashScopeResponse call(
            DashScopeRequest request,
            Map<String, String> additionalHeaders,
            Map<String, Object> additionalBodyParams,
            Map<String, String> additionalQueryParams) {
        String endpoint = selectEndpoint(request.getModel());
        String url = buildUrl(endpoint, additionalQueryParams);

        try {
            String requestBody = buildRequestBody(request, additionalBodyParams);
            log.debug("DashScope request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(false, additionalHeaders))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = transport.execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                throw new DashScopeHttpException(
                        "DashScope API request failed with status " + httpResponse.getStatusCode(),
                        httpResponse.getStatusCode(),
                        httpResponse.getBody());
            }

            String responseBody = httpResponse.getBody();
            log.debug("DashScope response: {}", responseBody);

            DashScopeResponse response =
                    objectMapper.readValue(responseBody, DashScopeResponse.class);

            if (response.isError()) {
                throw new DashScopeHttpException(
                        "DashScope API error: " + response.getMessage(),
                        response.getCode(),
                        responseBody);
            }

            return response;
        } catch (JsonProcessingException e) {
            throw new DashScopeHttpException("Failed to serialize/deserialize request", e);
        } catch (HttpTransportException e) {
            throw new DashScopeHttpException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * Make a streaming API call.
     *
     * @param request the DashScope request
     * @return a Flux of DashScope responses (one per SSE event)
     */
    public Flux<DashScopeResponse> stream(DashScopeRequest request) {
        return stream(request, null, null, null);
    }

    /**
     * Make a streaming API call with additional HTTP parameters.
     *
     * @param request the DashScope request
     * @param additionalHeaders additional HTTP headers to merge (may be null)
     * @param additionalBodyParams additional body parameters to merge (may be null)
     * @param additionalQueryParams additional query parameters to append (may be null)
     * @return a Flux of DashScope responses (one per SSE event)
     */
    public Flux<DashScopeResponse> stream(
            DashScopeRequest request,
            Map<String, String> additionalHeaders,
            Map<String, Object> additionalBodyParams,
            Map<String, String> additionalQueryParams) {
        String endpoint = selectEndpoint(request.getModel());
        String url = buildUrl(endpoint, additionalQueryParams);

        try {
            // Ensure incremental output is enabled for streaming
            if (request.getParameters() != null) {
                request.getParameters().setIncrementalOutput(true);
            }

            String requestBody = buildRequestBody(request, additionalBodyParams);
            log.debug("DashScope streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(true, additionalHeaders))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .map(
                            data -> {
                                try {
                                    return objectMapper.readValue(data, DashScopeResponse.class);
                                } catch (JsonProcessingException e) {
                                    log.warn(
                                            "Failed to parse SSE data: {}. Error: {}",
                                            data,
                                            e.getMessage());
                                    // Return null and filter out later
                                    return null;
                                }
                            })
                    .filter(response -> response != null)
                    .handle(
                            (response, sink) -> {
                                if (response.isError()) {
                                    sink.error(
                                            new DashScopeHttpException(
                                                    "DashScope API error: " + response.getMessage(),
                                                    response.getCode(),
                                                    null));
                                } else {
                                    sink.next(response);
                                }
                            });
        } catch (JsonProcessingException e) {
            return Flux.error(new DashScopeHttpException("Failed to serialize request", e));
        }
    }

    /**
     * Select the appropriate API endpoint based on model name.
     *
     * <p>Routing logic (consistent with existing SDK behavior):
     * <ul>
     *   <li>Models starting with "qvq" → multimodal API</li>
     *   <li>Models containing "-vl" → multimodal API</li>
     *   <li>All other models → text generation API</li>
     * </ul>
     *
     * @param modelName the model name
     * @return the API endpoint path
     */
    public String selectEndpoint(String modelName) {
        if (modelName == null) {
            return TEXT_GENERATION_ENDPOINT;
        }
        if (modelName.startsWith("qvq") || modelName.contains("-vl")) {
            log.debug("Using multimodal API for model: {}", modelName);
            return MULTIMODAL_GENERATION_ENDPOINT;
        }
        log.debug("Using text generation API for model: {}", modelName);
        return TEXT_GENERATION_ENDPOINT;
    }

    /**
     * Check if a model requires the multimodal API.
     *
     * @param modelName the model name
     * @return true if the model requires multimodal API
     */
    public boolean requiresMultimodalApi(String modelName) {
        return MULTIMODAL_GENERATION_ENDPOINT.equals(selectEndpoint(modelName));
    }

    private Map<String, String> buildHeaders(
            boolean streaming, Map<String, String> additionalHeaders) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.getUserAgent());

        if (streaming) {
            headers.put("X-DashScope-SSE", "enable");
        }

        // Merge additional headers (can override default headers)
        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            headers.putAll(additionalHeaders);
        }

        return headers;
    }

    /**
     * Build URL with query parameters.
     *
     * @param endpoint the API endpoint path
     * @param queryParams query parameters to append (may be null)
     * @return the full URL with query string
     */
    private String buildUrl(String endpoint, Map<String, String> queryParams) {
        String url = baseUrl + endpoint;
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }

        StringJoiner joiner = new StringJoiner("&", "?", "");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                log.warn("Skipping null query parameter: key={}, value={}", key, value);
                continue;
            }
            joiner.add(
                    URLEncoder.encode(key, StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return url + joiner.toString();
    }

    /**
     * Build request body with additional parameters merged.
     *
     * @param request the DashScope request
     * @param additionalBodyParams additional parameters to merge (may be null)
     * @return the serialized request body
     * @throws JsonProcessingException if serialization fails
     */
    private String buildRequestBody(
            DashScopeRequest request, Map<String, Object> additionalBodyParams)
            throws JsonProcessingException {
        String requestBody = objectMapper.writeValueAsString(request);

        if (additionalBodyParams == null || additionalBodyParams.isEmpty()) {
            return requestBody;
        }

        // Deserialize to Map, merge additional params, re-serialize
        Map<String, Object> bodyMap =
                objectMapper.readValue(requestBody, new TypeReference<Map<String, Object>>() {});
        bodyMap.putAll(additionalBodyParams);
        return objectMapper.writeValueAsString(bodyMap);
    }

    /**
     * Close the client and release resources.
     */
    public void close() {
        transport.close();
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
     * Create a new builder for DashScopeHttpClient.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for DashScopeHttpClient.
     */
    public static class Builder {
        private HttpTransport transport;
        private String apiKey;
        private String baseUrl;

        /**
         * Set the HTTP transport.
         *
         * @param transport the transport to use
         * @return this builder
         */
        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Set the API key.
         *
         * @param apiKey the DashScope API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Set the base URL.
         *
         * @param baseUrl the base URL (null for default)
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Build the DashScopeHttpClient.
         *
         * <p>If no transport is specified, the default transport from
         * {@link HttpTransportFactory#getDefault()} will be used, which provides
         * automatic lifecycle management and cleanup on JVM shutdown.
         *
         * @return a new DashScopeHttpClient instance
         */
        public DashScopeHttpClient build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            if (transport == null) {
                transport = HttpTransportFactory.getDefault();
            }
            return new DashScopeHttpClient(transport, apiKey, baseUrl);
        }
    }

    /**
     * Exception thrown when DashScope HTTP operations fail.
     */
    public static class DashScopeHttpException extends RuntimeException {
        private final Integer statusCode;
        private final String errorCode;
        private final String responseBody;

        public DashScopeHttpException(String message) {
            super(message);
            this.statusCode = null;
            this.errorCode = null;
            this.responseBody = null;
        }

        public DashScopeHttpException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.errorCode = null;
            this.responseBody = null;
        }

        public DashScopeHttpException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = null;
            this.responseBody = responseBody;
        }

        public DashScopeHttpException(String message, String errorCode, String responseBody) {
            super(message);
            this.statusCode = null;
            this.errorCode = errorCode;
            this.responseBody = responseBody;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
