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
 * HTTP response encapsulation for the transport layer.
 *
 * <p>This class represents an HTTP response with status code, headers, and body.
 */
public class HttpResponse {

    private final int statusCode;
    private final Map<String, String> headers;
    private final String body;

    private HttpResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
    }

    /**
     * Get the HTTP status code.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Get the response headers.
     *
     * @return an unmodifiable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the response body.
     *
     * @return the body string, or null if no body
     */
    public String getBody() {
        return body;
    }

    /**
     * Check if the response indicates success (2xx status code).
     *
     * @return true if status code is 2xx
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Create a new builder for HttpResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for HttpResponse.
     */
    public static class Builder {
        private int statusCode;
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        /**
         * Set the HTTP status code.
         *
         * @param statusCode the status code
         * @return this builder
         */
        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Add a header to the response.
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
         * Add multiple headers to the response.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Set the response body.
         *
         * @param body the body string
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Build the HttpResponse.
         *
         * @return a new HttpResponse instance
         */
        public HttpResponse build() {
            return new HttpResponse(this);
        }
    }
}
