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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.dashscope.dto.DashScopePublicKeyResponse;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
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

    /** Public keys API endpoint. */
    public static final String PUBLIC_KEYS_ENDPOINT = "/api/v1/public-keys/latest";

    private final HttpTransport transport;
    private final String apiKey;
    private final String baseUrl;
    private final String publicKeyId;
    private final String publicKey;

    /**
     * Create a new DashScopeHttpClient.
     *
     * @param transport the HTTP transport to use
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     * @param publicKeyId the RSA public key ID for encryption (null to disable encryption)
     * @param publicKey the RSA public key for encryption (Base64-encoded, null to disable encryption)
     */
    public DashScopeHttpClient(
            HttpTransport transport,
            String apiKey,
            String baseUrl,
            String publicKeyId,
            String publicKey) {
        this.transport = transport;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.publicKeyId = publicKeyId;
        this.publicKey = publicKey;
    }

    /**
     * Create a new DashScopeHttpClient.
     *
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     */
    public DashScopeHttpClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null, null);
    }

    /**
     * Create a new DashScopeHttpClient with default transport from factory.
     *
     * <p>Uses {@link HttpTransportFactory#getDefault()} for the transport, which
     * provides automatic lifecycle management and cleanup on JVM shutdown.
     *
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     * @param publicKeyId the RSA public key ID for encryption (null to disable encryption)
     * @param publicKey the RSA public key for encryption (Base64-encoded, null to disable encryption)
     */
    public DashScopeHttpClient(
            String apiKey, String baseUrl, String publicKeyId, String publicKey) {
        this(HttpTransportFactory.getDefault(), apiKey, baseUrl, publicKeyId, publicKey);
    }

    /**
     * Create a new DashScopeHttpClient with default transport and base URL.
     *
     * @param apiKey the DashScope API key
     */
    public DashScopeHttpClient(String apiKey) {
        this(apiKey, null, null, null);
    }

    /**
     * Check if encryption is enabled.
     *
     * @return true if both publicKeyId and publicKey are set
     */
    public boolean isEncryptionEnabled() {
        return publicKeyId != null
                && !publicKeyId.isEmpty()
                && publicKey != null
                && !publicKey.isEmpty();
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
            EncryptionContext encryptionContext = null;
            String requestBody = buildRequestBody(request, additionalBodyParams);
            if (isEncryptionEnabled()) {
                EncryptionResult encryptionResult = encryptRequestBodyIfNeeded(requestBody);
                requestBody = encryptionResult.requestBody;
                encryptionContext = encryptionResult.context;
            }
            final EncryptionContext finalEncryptionContext = encryptionContext;
            log.debug("DashScope request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(false, additionalHeaders, finalEncryptionContext))
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

            // Decrypt response if encryption is enabled
            if (finalEncryptionContext != null) {
                responseBody = decryptResponse(responseBody, finalEncryptionContext);
            }

            DashScopeResponse response =
                    JsonUtils.getJsonCodec().fromJson(responseBody, DashScopeResponse.class);

            if (response.isError()) {
                throw new DashScopeHttpException(
                        "DashScope API error: " + response.getMessage(),
                        response.getCode(),
                        responseBody);
            }

            return response;
        } catch (JsonException e) {
            throw new DashScopeHttpException("Failed to serialize/deserialize request", e);
        } catch (HttpTransportException e) {
            throw new DashScopeHttpException("HTTP transport error: " + e.getMessage(), e);
        }
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

            EncryptionContext encryptionContext = null;
            String requestBody = buildRequestBody(request, additionalBodyParams);
            if (isEncryptionEnabled()) {
                EncryptionResult encryptionResult = encryptRequestBodyIfNeeded(requestBody);
                requestBody = encryptionResult.requestBody;
                encryptionContext = encryptionResult.context;
            }
            final EncryptionContext finalEncryptionContext = encryptionContext;
            log.debug("DashScope streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(true, additionalHeaders, finalEncryptionContext))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .map(
                            data -> {
                                try {
                                    // Decrypt response if encryption is enabled
                                    if (finalEncryptionContext != null) {
                                        data = decryptResponse(data, finalEncryptionContext);
                                    }
                                    return JsonUtils.getJsonCodec()
                                            .fromJson(data, DashScopeResponse.class);
                                } catch (JsonException e) {
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
        } catch (JsonException e) {
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

    /**
     * Fetch the latest RSA public key from DashScope API.
     *
     * <p>This method calls the DashScope public keys API to retrieve the latest public key
     * and key ID for encryption purposes.
     *
     * @param apiKey the DashScope API key
     * @param baseUrl the base URL (null for default)
     * @param transport the HTTP transport to use
     * @return PublicKeyResult containing the public key ID and public key
     * @throws DashScopeHttpException if the request fails
     */
    public static PublicKeyResult fetchPublicKey(
            String apiKey, String baseUrl, HttpTransport transport) {
        String url = (baseUrl != null ? baseUrl : DEFAULT_BASE_URL) + PUBLIC_KEYS_ENDPOINT;

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", Version.getUserAgent());

            HttpRequest httpRequest =
                    HttpRequest.builder().url(url).method("GET").headers(headers).build();

            HttpResponse httpResponse = transport.execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                throw new DashScopeHttpException(
                        "Failed to fetch public key: HTTP " + httpResponse.getStatusCode(),
                        httpResponse.getStatusCode(),
                        httpResponse.getBody());
            }

            String responseBody = httpResponse.getBody();
            DashScopePublicKeyResponse response =
                    JsonUtils.getJsonCodec()
                            .fromJson(responseBody, DashScopePublicKeyResponse.class);

            if (response.isError()) {
                throw new DashScopeHttpException(
                        "Failed to fetch public key: " + response.getMessage(),
                        response.getCode(),
                        responseBody);
            }

            DashScopePublicKeyResponse.PublicKeyData data = response.getData();
            if (data == null || data.getPublicKey() == null || data.getPublicKeyId() == null) {
                throw new DashScopeHttpException(
                        "Invalid public key response: data is missing or incomplete",
                        null,
                        responseBody);
            }

            return new PublicKeyResult(data.getPublicKeyId(), data.getPublicKey());
        } catch (JsonException e) {
            throw new DashScopeHttpException("Failed to parse public key response", e);
        } catch (HttpTransportException e) {
            throw new DashScopeHttpException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * Result of fetching a public key from DashScope API.
     *
     * @param publicKeyId the public key ID
     * @param publicKey the Base64-encoded public key
     */
    public static record PublicKeyResult(String publicKeyId, String publicKey) {}

    private Map<String, String> buildHeaders(
            boolean streaming, Map<String, String> additionalHeaders, EncryptionContext context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.getUserAgent());

        if (streaming) {
            headers.put("X-DashScope-SSE", "enable");
        }

        // Add encryption header if encryption is enabled and we have a context for this request
        if (context != null) {
            String encryptionHeader = buildEncryptionHeader(context);
            if (encryptionHeader != null) {
                headers.put("X-DashScope-EncryptionKey", encryptionHeader);
            }
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
     */
    private String buildRequestBody(
            DashScopeRequest request, Map<String, Object> additionalBodyParams) {
        String requestBody = JsonUtils.getJsonCodec().toJson(request);

        if (additionalBodyParams == null || additionalBodyParams.isEmpty()) {
            return requestBody;
        }

        // Deserialize to Map, merge additional params, re-serialize
        Map<String, Object> bodyMap =
                JsonUtils.getJsonCodec()
                        .fromJson(requestBody, new TypeReference<Map<String, Object>>() {});
        bodyMap.putAll(additionalBodyParams);
        requestBody = JsonUtils.getJsonCodec().toJson(bodyMap);

        return requestBody;
    }

    /**
     * Encrypt the input field in the request body if present.
     *
     * @param requestBody the original request body JSON string
     * @return encryption result with possibly-updated body and context (null context if no input)
     */
    private EncryptionResult encryptRequestBodyIfNeeded(String requestBody) {
        try {
            // Parse request body to get input field
            Map<String, Object> bodyMap =
                    JsonUtils.getJsonCodec()
                            .fromJson(requestBody, new TypeReference<Map<String, Object>>() {});

            Object inputObj = bodyMap.get("input");
            if (inputObj == null) {
                return new EncryptionResult(requestBody, null); // No input to encrypt
            }

            // Serialize input to JSON string
            String inputJson = JsonUtils.getJsonCodec().toJson(inputObj);

            // Generate AES key and IV for this request
            javax.crypto.SecretKey aesSecretKey = DashScopeEncryptionUtils.generateAesSecretKey();
            byte[] iv = DashScopeEncryptionUtils.generateIv();

            // Encrypt input
            String encryptedInput =
                    DashScopeEncryptionUtils.encryptWithAes(aesSecretKey, iv, inputJson);

            // Encrypt AES key with RSA public key
            String encryptedAesKey =
                    DashScopeEncryptionUtils.encryptAesKeyWithRsa(aesSecretKey, publicKey);

            EncryptionContext context = new EncryptionContext(aesSecretKey, iv, encryptedAesKey);

            // Replace input with encrypted value
            bodyMap.put("input", encryptedInput);

            return new EncryptionResult(JsonUtils.getJsonCodec().toJson(bodyMap), context);
        } catch (Exception e) {
            log.error("Failed to encrypt request body", e);
            throw new DashScopeHttpException(
                    "Failed to encrypt request body: " + e.getMessage(), e);
        }
    }

    /**
     * Build encryption header with public key ID, encrypted AES key, and IV.
     *
     * @return the encryption header JSON string
     */
    private String buildEncryptionHeader(EncryptionContext context) {
        try {
            String ivBase64 = java.util.Base64.getEncoder().encodeToString(context.iv);

            // Build header JSON: {"public_key_id": "...", "encrypt_key": "...", "iv": "..."}
            Map<String, String> headerMap = new HashMap<>();
            headerMap.put("public_key_id", publicKeyId);
            headerMap.put("encrypt_key", context.encryptedAesKey);
            headerMap.put("iv", ivBase64);

            return JsonUtils.getJsonCodec().toJson(headerMap);
        } catch (Exception e) {
            log.error("Failed to build encryption header", e);
            throw new DashScopeHttpException(
                    "Failed to build encryption header: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt the output field in the response body.
     *
     * @param responseBody the encrypted response body JSON string
     * @return the decrypted response body JSON string
     */
    private String decryptResponse(String responseBody, EncryptionContext context) {
        try {
            // Parse response to get encrypted output
            Map<String, Object> responseMap =
                    JsonUtils.getJsonCodec()
                            .fromJson(responseBody, new TypeReference<Map<String, Object>>() {});

            Object outputObj = responseMap.get("output");
            if (outputObj == null || !(outputObj instanceof String)) {
                return responseBody; // No encrypted output to decrypt
            }

            String encryptedOutput = (String) outputObj;

            // Decrypt output
            String decryptedOutput =
                    DashScopeEncryptionUtils.decryptWithAes(
                            context.secretKey, context.iv, encryptedOutput);

            // Replace output with decrypted value (parse as JSON object)
            Object decryptedOutputObj =
                    JsonUtils.getJsonCodec().fromJson(decryptedOutput, Object.class);
            responseMap.put("output", decryptedOutputObj);

            return JsonUtils.getJsonCodec().toJson(responseMap);
        } catch (Exception e) {
            log.error("Failed to decrypt response body", e);
            // Return original response if decryption fails
            return responseBody;
        }
    }

    /**
     * Internal class to store encryption context (AES key, IV, and encrypted AES key).
     */
    private static class EncryptionContext {
        final javax.crypto.SecretKey secretKey;
        final byte[] iv;
        final String encryptedAesKey;

        EncryptionContext(javax.crypto.SecretKey secretKey, byte[] iv, String encryptedAesKey) {
            this.secretKey = secretKey;
            this.iv = iv;
            this.encryptedAesKey = encryptedAesKey;
        }
    }

    private static class EncryptionResult {
        final String requestBody;
        final EncryptionContext context;

        EncryptionResult(String requestBody, EncryptionContext context) {
            this.requestBody = requestBody;
            this.context = context;
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
        private String publicKeyId;
        private String publicKey;

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
         * Set the RSA public key ID for encryption.
         *
         * <p>When both publicKeyId and publicKey are set, requests and responses will be
         * encrypted using AES-GCM with RSA key exchange, following Aliyun's encryption protocol.
         *
         * @param publicKeyId the public key ID (null to disable encryption)
         * @return this builder
         */
        public Builder publicKeyId(String publicKeyId) {
            this.publicKeyId = publicKeyId;
            return this;
        }

        /**
         * Set the RSA public key for encryption (Base64-encoded).
         *
         * <p>When both publicKeyId and publicKey are set, requests and responses will be
         * encrypted using AES-GCM with RSA key exchange, following Aliyun's encryption protocol.
         *
         * @param publicKey the Base64-encoded RSA public key (null to disable encryption)
         * @return this builder
         */
        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
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
            return new DashScopeHttpClient(transport, apiKey, baseUrl, publicKeyId, publicKey);
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
