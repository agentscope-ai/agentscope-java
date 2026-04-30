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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an image content part in the AG-UI protocol's multimodal message format.
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "type": "image",
 *   "source": {"type": "url", "value": "https://...", "mimeType": "image/jpeg"},
 *   "metadata": {}
 * }
 * }</pre>
 */
public record AguiImageContent(AguiContentSource source, Map<String, Object> metadata)
        implements AguiContentPart {

    @JsonCreator
    public AguiImageContent(
            @JsonProperty("source") AguiContentSource source,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.metadata =
                metadata != null
                        ? Collections.unmodifiableMap(new HashMap<>(metadata))
                        : Collections.emptyMap();
    }

    /**
     * Creates an image content with source only (no metadata).
     *
     * @param source The content source
     */
    public AguiImageContent(AguiContentSource source) {
        this(source, null);
    }
}
