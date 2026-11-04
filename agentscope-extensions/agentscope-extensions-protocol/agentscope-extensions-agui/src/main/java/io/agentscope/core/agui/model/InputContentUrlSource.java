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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * URL source in the AG-UI protocol.
 *
 * <p>JSON form: {@code {"type": "url", "value": "https://...", "mimeType": "image/png",
 * "name": "report.pdf"}}
 *
 * <p>Maps to AgentScope's {@link io.agentscope.core.message.URLSource} during conversion:
 * {@code value} &rarr; {@code url}, {@code mimeType} &rarr; {@code mimeType}.
 *
 * @param value the URL pointing to the media content
 * @param mimeType the optional MIME type hint, may be null
 * @param name the optional display name, may be null; an extension beyond the protocol union so a
 *     document URL can carry its file name without borrowing a metadata key
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InputContentUrlSource(
        @JsonProperty("value") String value,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("name") String name)
        implements InputContentSource {

    /**
     * Compact constructor ensuring {@code value} is non-null.
     *
     * @param value the URL
     * @param mimeType the optional MIME type
     * @param name the optional display name
     */
    public InputContentUrlSource {
        Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * Convenience constructor without MIME type.
     *
     * @param value the URL
     */
    public InputContentUrlSource(String value) {
        this(value, null, null);
    }

    /**
     * Convenience constructor without a display name.
     *
     * @param value the URL
     * @param mimeType the optional MIME type
     */
    public InputContentUrlSource(String value, String mimeType) {
        this(value, mimeType, null);
    }
}
