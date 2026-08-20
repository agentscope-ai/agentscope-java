/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.a2a.server.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable inputs available to an A2A authentication resolver. */
public final class A2aAuthRequest {

    private final String transport;
    private final String method;
    private final Map<String, String> headers;
    private final Map<String, Object> transportMetadata;
    private final Map<String, Object> requestMetadata;

    /**
     * Backward-compatible constructor for resolvers that do not need transport coordinates.
     */
    public A2aAuthRequest(
            Map<String, String> headers,
            Map<String, Object> transportMetadata,
            Map<String, Object> requestMetadata) {
        this("", "", headers, transportMetadata, requestMetadata);
    }

    public A2aAuthRequest(
            String transport,
            String method,
            Map<String, String> headers,
            Map<String, Object> transportMetadata,
            Map<String, Object> requestMetadata) {
        this.transport = normalize(transport);
        this.method = normalize(method);
        this.headers = immutableStringMap(headers);
        this.transportMetadata = immutableObjectMap(transportMetadata);
        this.requestMetadata = immutableObjectMap(requestMetadata);
    }

    public String getTransport() {
        return transport;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        String value = headers.get(name);
        if (value != null) {
            return value;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the opaque Bearer credential, when the Authorization header uses that scheme.
     */
    public Optional<String> getBearerToken() {
        String authorization = getHeader("Authorization");
        if (authorization == null) {
            return Optional.empty();
        }
        String normalized = authorization.trim();
        int separator = normalized.indexOf(' ');
        if (separator <= 0 || !"Bearer".equalsIgnoreCase(normalized.substring(0, separator))) {
            return Optional.empty();
        }
        String token = normalized.substring(separator + 1).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    public Map<String, Object> getTransportMetadata() {
        return transportMetadata;
    }

    public Map<String, Object> getRequestMetadata() {
        return requestMetadata;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
