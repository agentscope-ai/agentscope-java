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
import java.util.UUID;

/**
 * Unified data block for arbitrary binary media (image / audio / video / file).
 *
 * <p>Unlike the legacy {@link ImageBlock}, {@link AudioBlock}, {@link VideoBlock}
 * subclasses — which the SDK retains for back-compat — {@code DataBlock} is the
 * forward-looking polymorphic container for every binary modality. New code
 * should prefer {@code DataBlock} over the legacy subclasses; the legacy types
 * stay around as valid {@link MsgRole#USER} payloads to keep existing pipelines
 * working.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DataBlock extends ContentBlock {

    private final Source source;

    private final String id;

    private final String name;

    /**
     * Creates a new data block for JSON deserialization.
     *
     * @param source The data source (URL or Base64); required
     * @param id Stable identifier; if null, a fresh UUID hex is generated
     * @param name Optional human-readable name (e.g. file name); may be null
     * @throws NullPointerException if source is null
     */
    @JsonCreator
    private DataBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.id =
                (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString().replace("-", "");
        this.name = name;
    }

    /**
     * Gets the source of this data block.
     *
     * @return The data source containing URL or Base64 data
     */
    public Source getSource() {
        return source;
    }

    /**
     * Gets the identifier of this data block.
     *
     * @return The block id; never null
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the optional name of this data block.
     *
     * @return The block name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Creates a new builder for constructing DataBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DataBlock instances.
     */
    public static class Builder {

        private Source source;

        private String id;

        private String name;

        /**
         * Sets the source for the data block.
         *
         * @param source The data source (URL or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the identifier for the data block. If left unset, a fresh UUID
         * is generated at {@link #build()} time.
         *
         * @param id The stable id
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the optional name for the data block.
         *
         * @param name The block name (e.g. file name)
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Builds a new DataBlock with the configured fields.
         *
         * @return A new DataBlock instance
         * @throws NullPointerException if source is null
         */
        public DataBlock build() {
            return new DataBlock(source, id, name);
        }
    }
}
