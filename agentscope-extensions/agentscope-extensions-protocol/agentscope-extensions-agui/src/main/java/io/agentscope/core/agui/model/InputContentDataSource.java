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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Inline data source in the AG-UI protocol.
 *
 * <p>JSON form: {@code {"type": "data", "value": "<base64>", "mimeType": "image/png"}}
 *
 * <p>Maps to AgentScope's {@link io.agentscope.core.message.Base64Source} during conversion:
 * {@code value} &rarr; {@code data}, {@code mimeType} &rarr; {@code mediaType}.
 *
 * @param value the Base64-encoded data content
 * @param mimeType the MIME type (e.g. "image/png")
 * @author shanhongyu
 * @since 2026-08-01
 */
public record InputContentDataSource(
        @JsonProperty("value") String value, @JsonProperty("mimeType") String mimeType)
        implements InputContentSource {

    /**
     * Compact constructor ensuring required fields are non-null.
     *
     * @param value the data content
     * @param mimeType the MIME type
     */
    public InputContentDataSource {
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(mimeType, "mimeType cannot be null");
    }
}
