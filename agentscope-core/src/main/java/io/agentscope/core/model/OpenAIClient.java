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
import io.agentscope.core.formatter.openai.dto.OpenAIError;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
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
 * Stateless HTTP client for OpenAI-compatible APIs.
 *
 * <p>This client handles communication with OpenAI's Chat Completion API using direct HTTP calls.
 * All configuration (API key, base URL) is passed per-request, making this client stateless and
 * safe to share across multiple model instances.
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
public class OpenAIClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClient.class);

    /** Default base URL for OpenAI API. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** Default base URL with version. */
    public static final String DEFAULT_BASE_URL_WITH_VERSION = DEFAULT_BASE_URL + "/v1";

    /** Chat completions API endpoint. */
    public static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";

    private final HttpTransport transport;

    /**
     * Create a new stateless OpenAIClient.
     *
     * @param transport the HTTP transport to use
     */
    public OpenAIClient(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * Create a new OpenAIClient with the default transport from factory.
     *
     * <p>Uses {@link io.agentscope.core.model.transport.HttpTransportFactory#getDefault()} for
     * the transport.
     */
    public OpenAIClient() {
        this(io.agentscope.core.model.transport.HttpTransportFactory.getDefault());
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile(".*/v\\d+$");

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
     * Build the complete API URL by intelligently combining base URL and endpoint path.
     *
     * @param baseUrl the base URL (already normalized, no trailing slash)
     * @param endpointPath the endpoint path to append (e.g., "/v1/chat/completions")
     * @return the complete API URL
     */
    private String buildApiUrl(String baseUrl, String endpointPath) {
        try {
            URI baseUri = URI.create(baseUrl);
            String basePath = baseUri.getPath();

            // If base URL already has a version path (e.g., /v1, /v4), remove /v1 from endpoint
            String adjustedEndpoint = endpointPath;
            if (basePath != null
                    && VERSION_PATTERN
                            .matcher(
                                    basePath.endsWith("/")
                                            ? basePath.substring(0, basePath.length() - 1)
                                            : basePath)
                            .matches()) {
                adjustedEndpoint =
                        endpointPath.startsWith("/v1/")
                                ? endpointPath.substring(3) // Remove "/v1/"
                                : (endpointPath.equals("/v1") ? "" : endpointPath);
            }

            // Join base path and endpoint path, handling slashes properly
            String finalPath = joinPaths(basePath, adjustedEndpoint);

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
            return buildApiUrlFallback(baseUrl, endpointPath);
        }
    }

    /**
     * Joins two path segments, handling trailing/leading slashes properly.
     *
     * @param path1 the first path segment (may be null or empty)
     * @param path2 the second path segment (may be null or empty)
     * @return the joined path
     */
    private String joinPaths(String path1, String path2) {
        if (path2 == null || path2.isEmpty()) {
            return path1 != null ? path1 : "";
        }
        if (path1 == null || path1.isEmpty()) {
            return path2.startsWith("/") ? path2 : "/" + path2;
        }

        // Remove trailing slash from path1
        String p1 = path1.endsWith("/") ? path1.substring(0, path1.length() - 1) : path1;
        // Remove leading slash from path2
        String p2 = path2.startsWith("/") ? path2.substring(1) : path2;

        return p1.isEmpty() ? "/" + p2 : p1 + "/" + p2;
    }

    /**
     * Fallback URL building using simple string concatenation.
     *
     * @param baseUrl the base URL
     * @param endpointPath the endpoint path
     * @return the complete URL
     */
    private String buildApiUrlFallback(String baseUrl, String endpointPath) {
        // Remove /v{number} from base URL if present, and /v1 from endpoint
        String normalizedBase = VERSION_PATTERN.matcher(baseUrl).replaceFirst("");
        String normalizedEndpoint =
                endpointPath.startsWith("/v1/") ? endpointPath.substring(3) : endpointPath;

        String separator = normalizedBase.endsWith("/") ? "" : "/";
        return normalizedBase
                + separator
                + (normalizedEndpoint.startsWith("/")
                        ? normalizedEndpoint.substring(1)
                        : normalizedEndpoint);
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
        return DEFAULT_BASE_URL_WITH_VERSION;
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
     * Make a synchronous API call.
     *
     * @param apiKey the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the OpenAI request
     * @return the OpenAI response
     * @throws OpenAIException if the request fails
     */
    public OpenAIResponse call(String apiKey, String baseUrl, OpenAIRequest request) {
        return call(apiKey, baseUrl, request, null);
    }

    /**
     * Make a synchronous API call with options.
     *
     * @param apiKey the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the OpenAI request
     * @param options additional options for headers and query params
     * @return the OpenAI response
     * @throws OpenAIException if the request fails
     */
    public OpenAIResponse call(
            String apiKey, String baseUrl, OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // Allow options to override apiKey, baseUrl, and endpointPath
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        // Get endpoint path from options or use default
        String endpointPath = CHAT_COMPLETIONS_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            // Ensure stream is false for non-streaming call
            request.setStream(false);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("OpenAI request to {}: {}", url, requestBody);

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
                        "OpenAI API request failed with status "
                                + statusCode
                                + " | "
                                + responseBody;
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
                response = JsonUtils.getJsonCodec().fromJson(responseBody, OpenAIResponse.class);
            } catch (JsonException e) {
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
        } catch (JsonException | HttpTransportException e) {
            throw new OpenAIException("Failed to execute request: " + e.getMessage(), e);
        }
    }

    /**
     * Make a streaming API call with options for headers and query params.
     *
     * @param apiKey the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the OpenAI request
     * @param options generation options containing additional headers and query params
     * @return a Flux of OpenAI responses (one per SSE event)
     */
    public Flux<OpenAIResponse> stream(
            String apiKey, String baseUrl, OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // Allow options to override apiKey, baseUrl, and endpointPath
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        // Get endpoint path from options or use default
        String endpointPath = CHAT_COMPLETIONS_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            // Enable streaming
            request.setStream(true);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("OpenAI streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
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
                                    return OpenAIException.create(
                                            ((HttpTransportException) ex).getStatusCode(),
                                            "HTTP transport error during streaming: "
                                                    + ex.getMessage(),
                                            null,
                                            ((HttpTransportException) ex).getResponseBody());
                                }
                                return ex;
                            });
        } catch (JsonException | HttpTransportException e) {
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
            OpenAIResponse response = JsonUtils.getJsonCodec().fromJson(data, OpenAIResponse.class);

            // Defensive null check after deserialization
            if (response == null) {
                log.warn(
                        "OpenAIResponse deserialization returned null for data: {}",
                        data.length() > 100 ? data.substring(0, 100) + "..." : data);
                return null;
            }
            return response;
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
     * @param apiKey the API key for authentication
     * @param options additional options for headers
     * @return map of headers
     */
    private Map<String, String> buildHeaders(String apiKey, GenerateOptions options) {
        return buildHeaders(apiKey, options, "application/json");
    }

    /**
     * Build HTTP headers for API requests.
     *
     * @param apiKey the API key for authentication
     * @param options additional options for headers
     * @param contentType the Content-Type header value (defaults to "application/json" if null)
     * @return map of headers
     */
    private Map<String, String> buildHeaders(
            String apiKey, GenerateOptions options, String contentType) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", contentType != null ? contentType : "application/json");

        // Add User-Agent header with fallback if Version.getUserAgent() returns null
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
     * @param apiKey the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param endpoint the API endpoint path (e.g., "/v1/images/generations")
     * @param requestBody the JSON request body as a Map or any serializable object
     * @return the raw HTTP response body as a String
     * @throws OpenAIException if the request fails
     */
    public String callApi(String apiKey, String baseUrl, String endpoint, Object requestBody) {
        return callApi(apiKey, baseUrl, endpoint, requestBody, null);
    }

    /**
     * Make a generic API call to any OpenAI endpoint with JSON request body and custom options.
     *
     * @param apiKey the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param endpoint the API endpoint path (e.g., "/v1/images/generations")
     * @param requestBody the JSON request body as a Map or any serializable object
     * @param options additional options for headers and query params
     * @return the raw HTTP response body as a String
     * @throws OpenAIException if the request fails
     */
    public String callApi(
            String apiKey,
            String baseUrl,
            String endpoint,
            Object requestBody,
            GenerateOptions options) {
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

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpoint);
        String url = buildUrl(apiUrl, options);

        try {
            String requestBodyJson =
                    requestBody instanceof String
                            ? (String) requestBody
                            : JsonUtils.getJsonCodec().toJson(requestBody);
            log.debug("OpenAI API request to {}: {}", url, requestBodyJson);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options, "application/json"))
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
        } catch (JsonException e) {
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
     * Get the HTTP transport used by this client.
     *
     * @return the HTTP transport
     */
    public HttpTransport getTransport() {
        return transport;
    }
}
