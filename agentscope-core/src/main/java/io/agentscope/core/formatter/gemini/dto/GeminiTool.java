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
import java.util.Map;

/**
 * Gemini Request Tool DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiTool {
    @JsonProperty("functionDeclarations")
    private List<GeminiFunctionDeclaration> functionDeclarations;

    @JsonProperty("googleSearchRetrieval")
    private Object googleSearchRetrieval; // Using Object schema for simple toggle

    @JsonProperty("codeExecution")
    private Object codeExecution;

    public List<GeminiFunctionDeclaration> getFunctionDeclarations() {
        return functionDeclarations;
    }

    public void setFunctionDeclarations(List<GeminiFunctionDeclaration> functionDeclarations) {
        this.functionDeclarations = functionDeclarations;
    }

    public Object getGoogleSearchRetrieval() {
        return googleSearchRetrieval;
    }

    public void setGoogleSearchRetrieval(Object googleSearchRetrieval) {
        this.googleSearchRetrieval = googleSearchRetrieval;
    }

    public Object getCodeExecution() {
        return codeExecution;
    }

    public void setCodeExecution(Object codeExecution) {
        this.codeExecution = codeExecution;
    }

    // Inner class for FunctionDeclaration
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiFunctionDeclaration {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("parameters")
        private Map<String, Object> parameters;

        @JsonProperty("response")
        private Map<String, Object> response; // Response schema if needed

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}
