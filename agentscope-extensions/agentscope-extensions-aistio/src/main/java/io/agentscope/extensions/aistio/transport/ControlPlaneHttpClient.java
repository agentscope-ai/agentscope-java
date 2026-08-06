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
package io.agentscope.extensions.aistio.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Shared JDK {@link HttpClient} wrapper for aistio control-plane REST calls.
 *
 * <p>Sends {@code X-Builder-Internal-Token} on every request and enforces a 10s timeout.
 */
public final class ControlPlaneHttpClient {

    /** HTTP response with a decoded string body. */
    public record Response(int status, String body) {}

    /** HTTP response with a raw byte body (e.g. snapshot octet-streams). */
    public record BytesResponse(int status, byte[] body) {}

    static final String INTERNAL_TOKEN_HEADER = "X-Builder-Internal-Token";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient http;

    /**
     * Creates a client against the given control-plane base URL.
     *
     * @param baseUrl control-plane HTTP base (trailing slash trimmed)
     * @param internalToken value for {@code X-Builder-Internal-Token}
     */
    public ControlPlaneHttpClient(String baseUrl, String internalToken) {
        this.baseUrl = trimSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken");
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /** Returns the trimmed base URL. */
    public String baseUrl() {
        return baseUrl;
    }

    /** Returns the Jackson mapper shared by control-plane clients. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Sends a JSON request and returns the string body.
     *
     * @param method HTTP method
     * @param path absolute path beginning with {@code /}, may include a query string
     * @param jsonBody request body object (serialized with Jackson), or {@code null} for no body
     */
    public Response send(String method, String path, Object jsonBody)
            throws IOException, InterruptedException {
        return send(method, path, jsonBody, Map.of());
    }

    /**
     * Sends a JSON request with extra headers.
     *
     * @param method HTTP method
     * @param path absolute path beginning with {@code /}
     * @param jsonBody request body object, or {@code null}
     * @param extraHeaders additional request headers
     */
    public Response send(
            String method, String path, Object jsonBody, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, extraHeaders);
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] json = MAPPER.writeValueAsBytes(jsonBody);
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body() == null ? "" : resp.body());
    }

    /**
     * Sends a raw-bytes request (e.g. snapshot upload) and returns the string body.
     *
     * @param method HTTP method
     * @param path absolute path beginning with {@code /}
     * @param contentType {@code Content-Type} header, or {@code null} to omit
     * @param body request body bytes, or {@code null}/{@code empty} for no body
     */
    public Response sendBytes(String method, String path, String contentType, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, Map.of());
        if (contentType != null && !contentType.isBlank()) {
            b.header("Content-Type", contentType);
        }
        if (body == null || body.length == 0) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body() == null ? "" : resp.body());
    }

    /**
     * Sends a request and returns the raw response bytes (used for snapshot download).
     *
     * @param method HTTP method
     * @param path absolute path beginning with {@code /}
     * @param contentType optional content type for a request body
     * @param body optional request body
     */
    public BytesResponse sendForBytes(String method, String path, String contentType, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = newRequest(method, path, Map.of());
        if (contentType != null && !contentType.isBlank()) {
            b.header("Content-Type", contentType);
        }
        if (body == null || body.length == 0) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = resp.body() == null ? new byte[0] : resp.body();
        return new BytesResponse(resp.statusCode(), respBody);
    }

    private HttpRequest.Builder newRequest(
            String method, String path, Map<String, String> extraHeaders) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(HTTP_TIMEOUT)
                        .header(INTERNAL_TOKEN_HEADER, internalToken);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        return b;
    }

    static String trimSlash(String url) {
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
