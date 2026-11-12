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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents a document that has already been uploaded to a model provider and is referenced by
 * that provider's own file identifier.
 *
 * <p>Providers that accept documents through a file ID (for example DashScope {@code qwen-long}
 * with {@code fileid://}) keep the file outside the request payload: the ID is the only thing the
 * request carries. Use this source when the upload is managed by the caller or by a
 * provider-specific client; use {@link URLSource} or {@link Base64Source} when the document bytes
 * travel with the request.
 *
 * <p>The provider and identifier are opaque to the SDK. The identifier is forwarded as-is by the
 * formatter of the provider named by {@link #getProvider()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileIdSource extends Source {

    private final String provider;

    @JsonProperty("file_id")
    private final String fileId;

    /**
     * Creates a new file ID source for JSON deserialization.
     *
     * @param provider The provider that owns the file identifier; required
     * @param fileId The provider-assigned file identifier; required
     * @throws NullPointerException if provider or fileId is null
     */
    @JsonCreator
    public FileIdSource(
            @JsonProperty("provider") String provider, @JsonProperty("file_id") String fileId) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.fileId = Objects.requireNonNull(fileId, "fileId cannot be null");
    }

    /**
     * Gets the provider that owns the file identifier.
     *
     * @return The provider identifier
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Gets the provider-assigned file identifier.
     *
     * @return The file ID as a string
     */
    public String getFileId() {
        return fileId;
    }

    /**
     * Creates a new builder for constructing FileIdSource instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileIdSource other)) {
            return false;
        }
        return Objects.equals(provider, other.provider) && Objects.equals(fileId, other.fileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, fileId);
    }

    /**
     * Builder for constructing FileIdSource instances.
     */
    public static class Builder {

        private String provider;
        private String fileId;

        /**
         * Sets the provider that owns the file identifier.
         *
         * @param provider The provider identifier
         * @return This builder for chaining
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Sets the provider-assigned file identifier.
         *
         * @param fileId The file ID
         * @return This builder for chaining
         */
        public Builder fileId(String fileId) {
            this.fileId = fileId;
            return this;
        }

        /**
         * Builds a new FileIdSource with the configured fields.
         *
         * @return A new FileIdSource instance
         * @throws NullPointerException if provider or fileId is null
         */
        public FileIdSource build() {
            return new FileIdSource(provider, fileId);
        }
    }
}
