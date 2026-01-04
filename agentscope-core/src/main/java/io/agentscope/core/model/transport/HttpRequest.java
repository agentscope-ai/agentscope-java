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
package io.agentscope.core.model.transport;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP request encapsulation for the transport layer.
 *
 * <p>This class represents an HTTP request with URL, method, headers, and body.
 * Use the builder pattern to construct requests.
 */
public class HttpRequest {

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;

    private HttpRequest(Builder builder) {
        this.url = builder.url;
        this.method = builder.method;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
    }

    /**
     * Get the request URL.
     *
     * @return the URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the HTTP method.
     *
     * @return the method (GET, POST, etc.)
     */
    public String getMethod() {
        return method;
    }

    /**
     * Get the request headers.
     *
     * @return an unmodifiable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the request body.
     *
     * @return the body string, or null if no body
     */
    public String getBody() {
        return body;
    }

    /**
     * Create a new builder for HttpRequest.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for HttpRequest.
     */
    public static class Builder {
        private String url;
        private String method = "GET";
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        /**
         * Set the request URL.
         *
         * @param url the URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Set the HTTP method.
         *
         * @param method the method (GET, POST, etc.)
         * @return this builder
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /**
         * Add a header to the request.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Add multiple headers to the request.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Set the request body.
         *
         * @param body the body string
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Build the HttpRequest.
         *
         * @return a new HttpRequest instance
         */
        public HttpRequest build() {
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URL is required");
            }
            return new HttpRequest(this);
        }
    }
}
