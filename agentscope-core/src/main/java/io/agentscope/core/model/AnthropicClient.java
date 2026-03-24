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

import io.agentscope.core.Version;
import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Stateless HTTP client for Anthropic's Messages API.
 *
 * <p>
 * This client handles communication with Anthropic's API using direct HTTP
 * calls via OkHttp.
 * All configuration (API key, base URL) is passed per-request, making this
 * client stateless and
 * safe to share across multiple model instances.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Synchronous and streaming request support</li>
 * <li>SSE stream parsing</li>
 * <li>JSON serialization/deserialization</li>
 * <li>Support for custom base URLs</li>
 * </ul>
 */
public class AnthropicClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    /** Default base URL for Anthropic API. */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** Messages API endpoint. */
    public static final String MESSAGES_ENDPOINT = "/v1/messages";

    /** Default Anthropic API version. */
    public static final String DEFAULT_API_VERSION = "2023-06-01";

    private final HttpTransport transport;

    /**
     * Create a new stateless AnthropicClient.
     *
     * @param transport the HTTP transport to use
     */
    public AnthropicClient(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * Create a new AnthropicClient with the default transport from factory.
     */
    public AnthropicClient() {
        this(io.agentscope.core.model.transport.HttpTransportFactory.getDefault());
    }

    /**
     * Normalize the base URL by removing trailing slashes.
     *
     * @param url the base URL to normalize
     * @return the normalized base URL (trailing slash removed)
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Get the effective base URL (options baseUrl or default).
     *
     * @param baseUrl the base URL from options
     * @return the effective base URL
     */
    private String getEffectiveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            return normalizeBaseUrl(baseUrl);
        }
        return DEFAULT_BASE_URL;
    }

    /**
     * Get the effective API key (options apiKey or null).
     *
     * @param apiKey the API key from options
     * @return the effective API key
     */
    private String getEffectiveApiKey(String apiKey) {
        return apiKey;
    }

    /**
     * Build the complete API URL.
     *
     * @param baseUrl the base URL
     * @return the complete API URL
     */
    private String buildApiUrl(String baseUrl) {
        return baseUrl + MESSAGES_ENDPOINT;
    }

    /**
     * Make a synchronous API call.
     *
     * @param apiKey  the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the Anthropic request
     * @return the Anthropic response
     * @throws ModelException if the request fails
     */
    public AnthropicResponse call(String apiKey, String baseUrl, AnthropicRequest request) {
        return call(apiKey, baseUrl, request, null);
    }

    /**
     * Make a synchronous API call with options.
     *
     * @param apiKey  the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the Anthropic request
     * @param options additional options for headers
     * @return the Anthropic response
     * @throws ModelException if the request fails
     */
    public AnthropicResponse call(
            String apiKey, String baseUrl, AnthropicRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // Allow options to override apiKey and baseUrl
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        String url = buildApiUrl(effectiveBaseUrl);

        try {
            // Ensure stream is false for non-streaming call
            request.setStream(false);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Anthropic request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                String errorMessage =
                        "Anthropic API request failed with status "
                                + statusCode
                                + " | "
                                + responseBody;
                throw new ModelException(errorMessage, null, request.getModel(), "anthropic");
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new ModelException(
                        "Anthropic API returned empty response body",
                        null,
                        request.getModel(),
                        "anthropic");
            }
            log.debug("Anthropic response: {}", responseBody);

            AnthropicResponse response;
            try {
                response = JsonUtils.getJsonCodec().fromJson(responseBody, AnthropicResponse.class);
            } catch (JsonException e) {
                throw new ModelException(
                        "Failed to parse Anthropic response: "
                                + e.getMessage()
                                + ". Response body: "
                                + responseBody,
                        e,
                        request.getModel(),
                        "anthropic");
            }

            // Defensive null check after deserialization
            if (response == null) {
                throw new ModelException(
                        "Anthropic API returned null response after deserialization",
                        null,
                        request.getModel(),
                        "anthropic");
            }

            return response;
        } catch (JsonException | HttpTransportException e) {
            throw new ModelException(
                    "Failed to execute request: " + e.getMessage(),
                    e,
                    request.getModel(),
                    "anthropic");
        }
    }

    /**
     * Make a streaming API call.
     *
     * @param apiKey  the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the Anthropic request
     * @param options generation options containing additional headers
     * @return a Flux of Anthropic stream events
     */
    public Flux<AnthropicStreamEvent> stream(
            String apiKey, String baseUrl, AnthropicRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // Allow options to override apiKey and baseUrl
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        String url = buildApiUrl(effectiveBaseUrl);

        try {
            // Enable streaming
            request.setStream(true);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Anthropic streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .filter(data -> !data.equals("[DONE]"))
                    .<AnthropicStreamEvent>handle(
                            (data, sink) -> {
                                AnthropicStreamEvent event = parseStreamData(data);
                                if (event != null) {
                                    sink.next(event);
                                }
                                // If event is null (malformed chunk), skip it silently
                            })
                    .onErrorMap(
                            ex -> {
                                if (ex instanceof HttpTransportException) {
                                    return new ModelException(
                                            "HTTP transport error during streaming: "
                                                    + ex.getMessage(),
                                            ex,
                                            request.getModel(),
                                            "anthropic");
                                }
                                return ex;
                            });
        } catch (JsonException | HttpTransportException e) {
            return Flux.error(
                    new ModelException(
                            "Failed to initialize request: " + e.getMessage(),
                            e,
                            request.getModel(),
                            "anthropic"));
        }
    }

    /**
     * Parse a single SSE data line to AnthropicStreamEvent.
     *
     * @param data the SSE data (without "data: " prefix)
     * @return the parsed AnthropicStreamEvent, or null if parsing fails
     */
    private AnthropicStreamEvent parseStreamData(String data) {
        if (log.isDebugEnabled()) {
            log.debug("SSE data: {}", data);
        }
        try {
            if (data == null || data.isEmpty()) {
                log.debug("Ignoring empty SSE data");
                return null;
            }
            AnthropicStreamEvent event =
                    JsonUtils.getJsonCodec().fromJson(data, AnthropicStreamEvent.class);

            // Defensive null check after deserialization
            if (event == null) {
                log.warn(
                        "AnthropicStreamEvent deserialization returned null for data: {}",
                        data.length() > 100 ? data.substring(0, 100) + "..." : data);
                return null;
            }
            return event;
        } catch (JsonException e) {
            log.error(
                    "Failed to parse SSE data - JSON error: {}. Content: {}.",
                    e.getMessage(),
                    data != null && data.length() > 100 ? data.substring(0, 100) + "..." : data);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse SSE data - unexpected error: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Build HTTP headers for API requests.
     *
     * @param apiKey  the API key for authentication
     * @param options additional options for headers
     * @return map of headers
     */
    private Map<String, String> buildHeaders(String apiKey, GenerateOptions options) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("x-api-key", apiKey);
        }
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", DEFAULT_API_VERSION);

        // Add User-Agent header
        String userAgent = Version.getUserAgent();
        headers.put("User-Agent", userAgent);

        // Apply additional headers from options
        if (options != null) {
            Map<String, String> additionalHeaders = options.getAdditionalHeaders();
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                headers.putAll(additionalHeaders);
            }
        }

        return headers;
    }

    /**
     * Execute HTTP request without internal retry (retry is handled by Model
     * layer).
     *
     * @param request the HTTP request
     * @return the HTTP response
     * @throws HttpTransportException if execution fails
     */
    HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return transport.execute(request);
    }

    /**
     * Get the HTTP transport used by this client.
     *
     * @return the HTTP transport
     */
    public HttpTransport getTransport() {
        return transport;
    }
}
