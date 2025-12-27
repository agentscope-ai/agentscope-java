/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.gemini.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Gemini Content DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiContent {
    @JsonProperty("role")
    private String role;

    @JsonProperty("parts")
    private List<GeminiPart> parts;

    public GeminiContent() {}

    public GeminiContent(String role, List<GeminiPart> parts) {
        this.role = role;
        this.parts = parts;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<GeminiPart> getParts() {
        return parts;
    }

    public void setParts(List<GeminiPart> parts) {
        this.parts = parts;
    }
}
