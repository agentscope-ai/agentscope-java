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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents URL-based media content.
 *
 * <p>This source references media files accessible via URLs, supporting
 * both remote HTTP/HTTPS URLs and local file URLs. This approach is
 * preferred when media files are hosted externally or when file size
 * makes Base64 encoding impractical.
 *
 * <p>Supported URL formats:
 * <ul>
 *   <li>Remote URLs: https://example.com/image.jpg</li>
 *   <li>Local files: file:///absolute/path/to/file.jpg</li>
 * </ul>
 *
 * <p>Using URL sources is more efficient for large media files and allows
 * the system to stream content rather than loading everything into memory.
 */
public class URLSource extends Source {

    private final String url;

    /**
     * Creates a new URL source for JSON deserialization.
     *
     * @param url The URL pointing to the media content
     * @throws NullPointerException if url is null
     */
    @JsonCreator
    public URLSource(@JsonProperty("url") String url) {
        this.url = Objects.requireNonNull(url, "url cannot be null");
    }

    /**
     * Gets the URL that points to the media content.
     *
     * @return The URL as a string
     */
    public String getUrl() {
        return url;
    }

    /**
     * Creates a new builder for constructing URLSource instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing URLSource instances.
     */
    public static class Builder {

        private String url;

        /**
         * Sets the URL for the media content.
         *
         * @param url The URL pointing to the media content
         * @return This builder for chaining
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Builds a new URLSource with the configured URL.
         *
         * @return A new URLSource instance
         * @throws NullPointerException if url is null
         */
        public URLSource build() {
            return new URLSource(url);
        }
    }
}
