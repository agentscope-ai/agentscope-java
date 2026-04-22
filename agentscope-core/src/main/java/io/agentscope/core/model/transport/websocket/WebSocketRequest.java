/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.model.transport.websocket;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket connection request configuration.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * WebSocketRequest request = WebSocketRequest.builder("wss://api.openai.com/v1/realtime")
 *     .header("Authorization", "Bearer " + apiKey)
 *     .header("OpenAI-Beta", "realtime=v1")
 *     .connectTimeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 */
public final class WebSocketRequest {

    private final String url;
    private final Map<String, String> headers;
    private final Duration connectTimeout;

    private WebSocketRequest(Builder builder) {
        this.url = builder.url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.connectTimeout = builder.connectTimeout;
    }

    /**
     * Get the WebSocket URL.
     *
     * @return WebSocket URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the request headers.
     *
     * @return Request headers (immutable)
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the connection timeout.
     *
     * @return Connection timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Create a new builder with the specified URL.
     *
     * @param url WebSocket URL
     * @return Builder instance
     */
    public static Builder builder(String url) {
        return new Builder(url);
    }

    /** Builder for WebSocketRequest. */
    public static class Builder {
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout = Duration.ofSeconds(30);

        private Builder(String url) {
            this.url = Objects.requireNonNull(url, "url is required");
        }

        /**
         * Add a header.
         *
         * @param name Header name
         * @param value Header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Add multiple headers.
         *
         * @param headers Headers to add
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Set the connection timeout.
         *
         * @param timeout Connection timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * Build the WebSocketRequest.
         *
         * @return WebSocketRequest instance
         */
        public WebSocketRequest build() {
            return new WebSocketRequest(this);
        }
    }
}
