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
import java.util.Objects;

/**
 * Represents a URL-based content source in the AG-UI protocol.
 *
 * <p>JSON format: {@code {"type": "url", "value": "https://...", "mimeType": "image/jpeg"}}
 *
 * <p>The {@code mimeType} field is optional for URL sources.
 */
public record AguiUrlSource(String value, String mimeType) implements AguiContentSource {

    @JsonCreator
    public AguiUrlSource(
            @JsonProperty("value") String value, @JsonProperty("mimeType") String mimeType) {
        this.value = Objects.requireNonNull(value, "value cannot be null");
        this.mimeType = mimeType; // optional for URL sources
    }
}
