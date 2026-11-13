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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * Document input content in the AG-UI protocol.
 *
 * <p>JSON form: {@code {"type": "document", "id": "...", "source": {"type": "url", "value": "..."},
 * "metadata": {...}}}
 *
 * <p>It maps to AgentScope's {@code DocumentBlock} during conversion.
 *
 * @param id optional identifier of this part within its message
 * @param source the document source (URL, data, or file), must not be null
 * @param metadata optional metadata, may be null
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentInputContent(
        @JsonProperty("id") String id,
        @JsonProperty("source") InputContentSource source,
        @JsonProperty("metadata") Map<String, Object> metadata)
        implements InputContent {

    /**
     * Compact constructor ensuring {@code source} is non-null.
     *
     * @param id optional part identifier
     * @param source the document source
     * @param metadata optional metadata
     */
    public DocumentInputContent {
        Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Creates a document part without a part identifier.
     *
     * @param source the document source
     * @param metadata optional metadata
     */
    public DocumentInputContent(InputContentSource source, Map<String, Object> metadata) {
        this(null, source, metadata);
    }
}
