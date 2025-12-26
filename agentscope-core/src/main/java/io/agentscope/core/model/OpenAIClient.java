/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.openai.dto.OpenAIError;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * HTTP client for OpenAI Chat Completion API.
 *
 * <p>This client handles communication with OpenAI's Chat Completion API
 * using direct HTTP calls via OkHttp, without depending on the OpenAI Java SDK.
 *
 * <p>Features:
 * <ul>
 *   <li>Synchronous and streaming request support</li>
 *   <li>SSE stream parsing</li>
 *   <li>JSON serialization/deserialization</li>
 *   <li>Support for OpenAI-compatible APIs (custom base URL)</li>
 *   <li>Generic API call support for other OpenAI endpoints (images, audio, etc.)</li>
 * </ul>
 *
 * <p>API endpoints:
 * <ul>
 *   <li>Chat completions: /v1/chat/completions</li>
 *   <li>Images: /v1/images/generations, /v1/images/edits, /v1/images/variations</li>
 *   <li>Audio: /v1/audio/speech, /v1/audio/transcriptions, /v1/audio/translations</li>
 * </ul>
 */
public class OpenAIClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClient.class);

    /** Default base URL for OpenAI API. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** Chat completions API endpoint. */
    public static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";

    private final HttpTransport transport;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    /**
     * Create a new OpenAIClient.
     *
     * @param transport the HTTP transport to use
     * @param apiKey the OpenAI API key
     * @param baseUrl the base URL (null for default)
     */
    public OpenAIClient(HttpTransport transport, String apiKey, String baseUrl) {
        this.transport = transport;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? normalizeBaseUrl(baseUrl) : DEFAULT_BASE_URL;
        this.objectMapper = createObjectMapper(); // Instance-level ObjectMapper for thread safety
    }

    /**
     * Create a new OpenAIClient with default transport from factory.
     *
     * <p>Uses {@link HttpTransportFactory#getDefault()} for the transport, which
     * provides automatic lifecycle management and cleanup on JVM shutdown.
     *
     * @param apiKey the OpenAI API key
     * @param baseUrl the base URL (null for default)
     */
    public OpenAIClient(String apiKey, String baseUrl) {
        this(HttpTransportFactory.getDefault(), apiKey, baseUrl);
    }

    /**
     * Create a new OpenAIClient with default transport and base URL.
     *
     * @param apiKey the OpenAI API key
     */
    public OpenAIClient(String apiKey) {
        this(apiKey, null);
    }

    /**
     * Normalize the base URL by removing trailing slashes.
     *
     * <p>This method is specific to OpenAI-compatible APIs (used by OpenAIClient).
     * It does not affect other HTTP clients like DashScopeHttpClient.
     *
     * <p>The actual path handling (including /v1 detection) is done in
     * {@link #buildApiUrl(String)} to ensure proper URL construction.
     *
     * @param url the base URL to normalize
     * @return the normalized base URL (trailing slash removed)
     */
    private String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        // Remove trailing slash if present
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile(".*/v\\d+$");

    /**
     * Build the complete API URL by intelligently combining base URL and endpoint path.
     *
     * <p>This method handles various base URL formats for OpenAI-compatible APIs:
     * <ul>
     *   <li>https://api.example.com → https://api.example.com/v1/chat/completions</li>
     *   <li>https://api.example.com/v1 → https://api.example.com/v1/chat/completions</li>
     *   <li>https://api.example.com/v1/ → https://api.example.com/v1/chat/completions</li>
     *   <li>https://dashscope.aliyuncs.com/compatible-mode/v1 → https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions</li>
     *   <li>https://open.bigmodel.cn/api/paas/v4 → https://open.bigmodel.cn/api/paas/v4/chat/completions</li>
     * </ul>
     *
     * <p>The method uses URI parsing to properly handle path segments and avoid
     * duplicate version paths. It detects if the base URL path ends with "/v{number}" (e.g., "/v1", "/v4")
     * and automatically adjusts the endpoint path accordingly.
     *
     * @param baseUrl the base URL (already normalized, no trailing slash)
     * @param endpointPath the endpoint path to append (e.g., "/v1/chat/completions")
     * @return the complete API URL
     */
    private String buildApiUrl(String baseUrl, String endpointPath) {
        try {
            URI baseUri = URI.create(baseUrl);
            String basePath = baseUri.getPath();

            // Check if base URL path ends with /v{number} (e.g., /v1, /v4) to handle various API
            // versions
            // This supports OpenAI-compatible APIs that use different version numbers (v1, v2, v3,
            // v4, etc.)
            boolean pathEndsWithVersion = false;
            if (basePath != null && !basePath.isEmpty()) {
                // Remove trailing slash for comparison
                String pathWithoutTrailingSlash =
                        basePath.endsWith("/")
                                ? basePath.substring(0, basePath.length() - 1)
                                : basePath;
                // Check if path ends with /v followed by digits (e.g., /v1, /v4, /v10)
                pathEndsWithVersion = VERSION_PATTERN.matcher(pathWithoutTrailingSlash).matches();
            }

            // Determine the final endpoint path to append
            String finalEndpointPath;
            if (pathEndsWithVersion) {
                // Base URL already has a version path (e.g., /v1, /v4), so remove /v1 prefix from
                // endpoint path
                // endpointPath is "/v1/chat/completions", we need "/chat/completions"
                if (endpointPath.startsWith("/v1/")) {
                    finalEndpointPath = endpointPath.substring(3); // Remove "/v1"
                } else if (endpointPath.equals("/v1")) {
                    finalEndpointPath = "";
                } else {
                    finalEndpointPath =
                            endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
                }
            } else {
                // Base URL doesn't have a version path, use the full endpoint path
                finalEndpointPath =
                        endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
            }

            // Build the final path by combining base path and endpoint path
            String finalPath;
            if (basePath == null || basePath.isEmpty()) {
                finalPath = finalEndpointPath;
            } else if (finalEndpointPath == null || finalEndpointPath.isEmpty()) {
                // Endpoint path is empty, use base path as-is
                finalPath = basePath;
            } else {
                // Ensure proper path joining (handle trailing/leading slashes)
                if (basePath.endsWith("/")) {
                    // Base path ends with /, remove leading / from endpoint if present
                    finalPath =
                            basePath
                                    + (finalEndpointPath.startsWith("/")
                                            ? finalEndpointPath.substring(1)
                                            : finalEndpointPath);
                } else {
                    // Base path doesn't end with /, add / before endpoint if needed
                    finalPath =
                            basePath
                                    + (finalEndpointPath.startsWith("/")
                                            ? finalEndpointPath
                                            : "/" + finalEndpointPath);
                }
            }

            // Build the final URI, preserving scheme, authority, query, and fragment
            URI finalUri =
                    new URI(
                            baseUri.getScheme(),
                            baseUri.getAuthority(),
                            finalPath,
                            baseUri.getQuery(),
                            baseUri.getFragment());

            return finalUri.toString();
        } catch (Exception e) {
            // Fallback to simple string concatenation if URI parsing fails
            log.warn(
                    "Failed to parse base URL as URI, using simple concatenation: {}",
                    e.getMessage());
            // Simple fallback: if baseUrl ends with /v{number}, remove /v1 from endpoint
            String normalizedBase = baseUrl;
            String normalizedEndpoint = endpointPath;
            // Check if base URL ends with /v followed by digits (e.g., /v1, /v4)
            if (VERSION_PATTERN
                    .matcher(
                            normalizedBase.endsWith("/")
                                    ? normalizedBase.substring(0, normalizedBase.length() - 1)
                                    : normalizedBase)
                    .matches()) {
                // If base has version, remove version from ENDPOINT to avoid duplication
                if (normalizedEndpoint.startsWith("/v1/")) {
                    normalizedEndpoint = normalizedEndpoint.substring(3);
                }
            }

            String separator = normalizedBase.endsWith("/") ? "" : "/";
            return normalizedBase
                    + separator
                    + (normalizedEndpoint.startsWith("/")
                            ? normalizedEndpoint.substring(1)
                            : normalizedEndpoint);
        }
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
     * @param request the OpenAI request
     * @return the OpenAI response
     * @throws OpenAIException if the request fails
     */
    public OpenAIResponse call(OpenAIRequest request) {
        return call(request, null);
    }

    /**
     * Make a synchronous API call with options.
     *
     * @param request the OpenAI request
     * @param options additional options for headers and query params
     * @return the OpenAI response
     * @throws OpenAIException if the request fails
     */
    public OpenAIResponse call(OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");
        String apiUrl = buildApiUrl(baseUrl, CHAT_COMPLETIONS_ENDPOINT);
        String url = buildUrl(apiUrl, options);

        try {
            // Ensure stream is false for non-streaming call
            request.setStream(false);

            String requestBody = objectMapper.writeValueAsString(request);
            log.debug("OpenAI request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(options))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                String errorMessage = "OpenAI API request failed with status " + statusCode;
                throw OpenAIException.create(statusCode, errorMessage, null, responseBody);
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new OpenAIException(
                        "OpenAI API returned empty response body",
                        httpResponse.getStatusCode(),
                        null);
            }
            log.debug("OpenAI response: {}", responseBody);

            OpenAIResponse response;
            try {
                response = objectMapper.readValue(responseBody, OpenAIResponse.class);
            } catch (JsonProcessingException e) {
                throw new OpenAIException(
                        "Failed to parse OpenAI response: "
                                + e.getMessage()
                                + ". Response body: "
                                + responseBody,
                        e);
            }

            // Defensive null check after deserialization
            if (response == null) {
                throw new OpenAIException(
                        "OpenAI API returned null response after deserialization",
                        httpResponse.getStatusCode(),
                        responseBody);
            }

            if (response.isError()) {
                OpenAIError error = response.getError();
                if (error == null) {
                    throw new OpenAIException(
                            "OpenAI API returned error but error details are null",
                            400,
                            "unknown_error",
                            responseBody);
                }
                String errorMessage =
                        error.getMessage() != null ? error.getMessage() : "Unknown error";
                String errorCode = error.getCode() != null ? error.getCode() : "unknown_error";
                throw OpenAIException.create(
                        httpResponse.getStatusCode(),
                        "OpenAI API error: " + errorMessage,
                        errorCode,
                        responseBody);
            }

            return response;
        } catch (JsonProcessingException | HttpTransportException e) {
            throw new OpenAIException("Failed to execute request: " + e.getMessage(), e);
        }
    }

    /**
     * Make a streaming API call.
     *
     * @param request the OpenAI request
     * @return a Flux of OpenAI responses (one per SSE event)
     */
    public Flux<OpenAIResponse> stream(OpenAIRequest request) {
        return stream(request, null);
    }

    /**
     * Make a streaming API call with options for headers and query params.
     *
     * @param request the OpenAI request
     * @param options generation options containing additional headers and query params
     * @return a Flux of OpenAI responses (one per SSE event)
     */
    public Flux<OpenAIResponse> stream(OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");
        String apiUrl = buildApiUrl(baseUrl, CHAT_COMPLETIONS_ENDPOINT);
        String url = buildUrl(apiUrl, options);

        try {
            // Enable streaming
            request.setStream(true);
            // Enable usage statistics in streaming response
            // request.setStreamOptions(OpenAIStreamOptions.withUsage());

            String requestBody = objectMapper.writeValueAsString(request);
            log.debug("OpenAI streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(options))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .filter(data -> !data.equals("[DONE]"))
                    .<OpenAIResponse>handle(
                            (data, sink) -> {
                                OpenAIResponse response = parseStreamData(data);
                                if (response != null) {
                                    // Check for error in streaming response chunk
                                    if (response.isError()) {
                                        OpenAIError error = response.getError();
                                        String errorMessage =
                                                error != null && error.getMessage() != null
                                                        ? error.getMessage()
                                                        : "Unknown error in streaming response";
                                        String errorCode =
                                                error != null && error.getCode() != null
                                                        ? error.getCode()
                                                        : null;
                                        sink.error(
                                                OpenAIException.create(
                                                        400,
                                                        "OpenAI API error in streaming response: "
                                                                + errorMessage,
                                                        errorCode,
                                                        null));
                                        return;
                                    }
                                    sink.next(response);
                                }
                                // If response is null (malformed chunk), skip it silently
                            })
                    .onErrorMap(
                            ex -> {
                                if (ex instanceof HttpTransportException) {
                                    return new OpenAIException(
                                            "HTTP transport error during streaming: "
                                                    + ex.getMessage(),
                                            ex);
                                }
                                return ex;
                            });
        } catch (JsonProcessingException | HttpTransportException e) {
            return Flux.error(
                    new OpenAIException("Failed to initialize request: " + e.getMessage(), e));
        }
    }

    /**
     * Parse a single SSE data line to OpenAIResponse.
     *
     * @param data the SSE data (without "data: " prefix)
     * @return the parsed OpenAIResponse, or null if parsing fails
     */
    private OpenAIResponse parseStreamData(String data) {
        if (log.isDebugEnabled()) {
            log.debug("SSE data: {}", data);
        }
        try {
            if (data == null || data.isEmpty()) {
                log.debug("Ignoring empty SSE data");
                return null;
            }
            OpenAIResponse response = objectMapper.readValue(data, OpenAIResponse.class);

            // Defensive null check after deserialization
            if (response == null) {
                log.warn(
                        "OpenAIResponse deserialization returned null for data: {}",
                        data.length() > 100 ? data.substring(0, 100) + "..." : data);
                return null;
            }
            return response;
        } catch (JsonProcessingException e) {
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

    private Map<String, String> buildHeaders() {
        return buildHeaders(null);
    }

    private Map<String, String> buildHeaders(GenerateOptions options) {
        return buildHeaders(options, "application/json");
    }

    /**
     * Build HTTP headers for API requests.
     *
     * @param options additional options for headers
     * @param contentType the Content-Type header value (defaults to "application/json" if null)
     * @return map of headers
     */
    private Map<String, String> buildHeaders(GenerateOptions options, String contentType) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", contentType != null ? contentType : "application/json");

        // Add User-Agent header with fallback if Version.getUserAgent() returns null
        String userAgent = Version.getUserAgent();
        headers.put("User-Agent", userAgent != null ? userAgent : "agentscope-java/1.0");

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
     * Build URL with optional query parameters.
     */
    private String buildUrl(String baseUrl, GenerateOptions options) {
        if (options == null) {
            return baseUrl;
        }

        Map<String, String> queryParams = options.getAdditionalQueryParams();
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl;
        }

        StringBuilder url = new StringBuilder(baseUrl);
        boolean first = !baseUrl.contains("?");

        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            url.append(first ? "?" : "&");
            first = false;
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && value != null) {
                url.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }

        return url.toString();
    }

    /**
     * Make a generic API call to any OpenAI endpoint with JSON request body.
     *
     * <p>This method can be used for endpoints other than chat completions, such as:
     * <ul>
     *   <li>/v1/images/generations</li>
     *   <li>/v1/audio/speech</li>
     *   <li>Other JSON-based OpenAI API endpoints</li>
     * </ul>
     *
     * @param endpoint the API endpoint path (e.g., "/v1/images/generations")
     * @param requestBody the JSON request body as a Map or any serializable object
     * @return the raw HTTP response body as a String
     * @throws OpenAIException if the request fails
     */
    public String callApi(String endpoint, Object requestBody) {
        return callApi(endpoint, requestBody, null);
    }

    /**
     * Make a generic API call to any OpenAI endpoint with JSON request body and custom options.
     *
     * @param endpoint the API endpoint path (e.g., "/v1/images/generations")
     * @param requestBody the JSON request body as a Map or any serializable object
     * @param options additional options for headers and query params
     * @return the raw HTTP response body as a String
     * @throws OpenAIException if the request fails
     */
    public String callApi(String endpoint, Object requestBody, GenerateOptions options) {
        String apiUrl = buildApiUrl(baseUrl, endpoint);
        String url = buildUrl(apiUrl, options);

        try {
            String requestBodyJson =
                    requestBody instanceof String
                            ? (String) requestBody
                            : objectMapper.writeValueAsString(requestBody);
            log.debug("OpenAI API request to {}: {}", url, requestBodyJson);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(options, "application/json"))
                            .body(requestBodyJson)
                            .build();

            HttpResponse httpResponse = execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                String errorMessage = "OpenAI API request failed with status " + statusCode;
                throw OpenAIException.create(statusCode, errorMessage, null, responseBody);
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new OpenAIException(
                        "OpenAI API returned empty response body",
                        httpResponse.getStatusCode(),
                        null);
            }
            log.debug("OpenAI API response: {}", responseBody);
            return responseBody;
        } catch (JsonProcessingException e) {
            throw new OpenAIException("Failed to serialize request", e);
        } catch (HttpTransportException e) {
            throw new OpenAIException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * Execute HTTP request without internal retry (retry is handled by Model layer).
     *
     * @param request the HTTP request
     * @return the HTTP response
     * @throws OpenAIException if execution fails
     */
    HttpResponse execute(HttpRequest request) {
        try {
            return transport.execute(request);
        } catch (HttpTransportException e) {
            throw new OpenAIException("HTTP transport failed: " + e.getMessage(), e);
        }
    }

    /**
     * Close the client and release resources.
     *
     * <p>If the transport is managed by {@link HttpTransportFactory} (e.g., the default
     * transport), it will not be closed here as it's shared and managed by the factory.
     * Only transports explicitly provided to this client will be closed.
     *
     * @throws IOException if an I/O error occurs during close
     */
    @Override
    public void close() throws IOException {
        try {
            // Only close transport if it's not managed by the factory
            // Managed transports are closed automatically on JVM shutdown
            if (!HttpTransportFactory.isManaged(transport)) {
                transport.close();
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to close HTTP transport", e);
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
     * Create a new builder for OpenAIClient.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OpenAIClient.
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
         * @param apiKey the OpenAI API key
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
         * Build the OpenAIClient.
         *
         * <p>If no transport is specified, the default transport from
         * {@link HttpTransportFactory#getDefault()} will be used, which provides
         * automatic lifecycle management and cleanup on JVM shutdown.
         *
         * @return a new OpenAIClient instance
         * @throws IllegalArgumentException if API key is null, empty, or invalid
         */
        public OpenAIClient build() {
            // Validate API key
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API key is required and cannot be empty");
            }

            String trimmedApiKey = apiKey.trim();
            // Check for invalid whitespace characters (server will validate format/length)
            if (trimmedApiKey.contains(" ")
                    || trimmedApiKey.contains("\n")
                    || trimmedApiKey.contains("\r")
                    || trimmedApiKey.contains("\t")) {
                throw new IllegalArgumentException(
                        "API key contains invalid characters (whitespace)");
            }

            if (transport == null) {
                transport = HttpTransportFactory.getDefault();
            }
            return new OpenAIClient(transport, trimmedApiKey, baseUrl);
        }
    }
}
