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
 * Represents a text content part in the AG-UI protocol's multimodal message format.
 *
 * <p>JSON format: {@code {"type": "text", "text": "..."}}
 */
public record AguiTextContent(String text) implements AguiContentPart {

    @JsonCreator
    public AguiTextContent(@JsonProperty("text") String text) {
        this.text = Objects.requireNonNull(text, "text cannot be null");
    }
}
