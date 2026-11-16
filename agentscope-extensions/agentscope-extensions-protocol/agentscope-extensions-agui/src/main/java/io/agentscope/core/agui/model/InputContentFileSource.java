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
 * Provider-issued file handle in the AG-UI protocol.
 *
 * <p>JSON form: {@code {"type": "file", "value": "file-abc", "provider": "openai",
 * "mimeType": "application/pdf"}}
 *
 * <p>The value is an opaque handle the provider issued and is never fetched by the transport. It
 * maps to AgentScope's {@link io.agentscope.core.message.FileIdSource} during conversion, with the
 * optional {@code provider} naming who issued the handle. {@code mimeType} is optional because
 * the provider that holds the bytes knows the file type.
 *
 * @param value the provider-issued file handle, must not be null
 * @param provider who issued the handle, may be null
 * @param mimeType the MIME type, may be null
 * @author shanhongyu
 * @since 2026-11-04
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InputContentFileSource(
        @JsonProperty("value") String value,
        @JsonProperty("provider") String provider,
        @JsonProperty("mimeType") String mimeType)
        implements InputContentSource {

    /**
     * Compact constructor ensuring the handle is non-null.
     *
     * @param value the provider-issued file handle
     * @param provider who issued the handle, may be null
     * @param mimeType the MIME type, may be null
     */
    public InputContentFileSource {
        Objects.requireNonNull(value, "value cannot be null");
    }
}
