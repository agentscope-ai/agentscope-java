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
package io.agentscope.core.formatter.gemini.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Gemini API Request DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiRequest {

    @JsonProperty("contents")
    private List<GeminiContent> contents;

    @JsonProperty("tools")
    private List<GeminiTool> tools;

    @JsonProperty("toolConfig")
    private GeminiToolConfig toolConfig;

    @JsonProperty("safetySettings")
    private List<GeminiSafetySetting> safetySettings;

    @JsonProperty("systemInstruction")
    private GeminiContent systemInstruction;

    @JsonProperty("generationConfig")
    private GeminiGenerationConfig generationConfig;

    public List<GeminiContent> getContents() {
        return contents;
    }

    public void setContents(List<GeminiContent> contents) {
        this.contents = contents;
    }

    public List<GeminiTool> getTools() {
        return tools;
    }

    public void setTools(List<GeminiTool> tools) {
        this.tools = tools;
    }

    public GeminiToolConfig getToolConfig() {
        return toolConfig;
    }

    public void setToolConfig(GeminiToolConfig toolConfig) {
        this.toolConfig = toolConfig;
    }

    public List<GeminiSafetySetting> getSafetySettings() {
        return safetySettings;
    }

    public void setSafetySettings(List<GeminiSafetySetting> safetySettings) {
        this.safetySettings = safetySettings;
    }

    public GeminiContent getSystemInstruction() {
        return systemInstruction;
    }

    public void setSystemInstruction(GeminiContent systemInstruction) {
        this.systemInstruction = systemInstruction;
    }

    public GeminiGenerationConfig getGenerationConfig() {
        return generationConfig;
    }

    public void setGenerationConfig(GeminiGenerationConfig generationConfig) {
        this.generationConfig = generationConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<GeminiContent> contents;
        private List<GeminiTool> tools;
        private GeminiToolConfig toolConfig;
        private List<GeminiSafetySetting> safetySettings;
        private GeminiContent systemInstruction;
        private GeminiGenerationConfig generationConfig;

        public Builder contents(List<GeminiContent> contents) {
            this.contents = contents;
            return this;
        }

        public Builder tools(List<GeminiTool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolConfig(GeminiToolConfig toolConfig) {
            this.toolConfig = toolConfig;
            return this;
        }

        public Builder safetySettings(List<GeminiSafetySetting> safetySettings) {
            this.safetySettings = safetySettings;
            return this;
        }

        public Builder systemInstruction(GeminiContent systemInstruction) {
            this.systemInstruction = systemInstruction;
            return this;
        }

        public Builder generationConfig(GeminiGenerationConfig generationConfig) {
            this.generationConfig = generationConfig;
            return this;
        }

        public GeminiRequest build() {
            GeminiRequest request = new GeminiRequest();
            request.setContents(contents);
            request.setTools(tools);
            request.setToolConfig(toolConfig);
            request.setSafetySettings(safetySettings);
            request.setSystemInstruction(systemInstruction);
            request.setGenerationConfig(generationConfig);
            return request;
        }
    }
}
