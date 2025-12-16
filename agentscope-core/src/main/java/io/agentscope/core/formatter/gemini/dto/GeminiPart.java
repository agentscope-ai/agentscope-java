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
import java.util.Map;

/**
 * Gemini Part DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiPart {
    @JsonProperty("text")
    private String text;

    @JsonProperty("functionCall")
    private GeminiFunctionCall functionCall;

    @JsonProperty("functionResponse")
    private GeminiFunctionResponse functionResponse;

    @JsonProperty("inlineData")
    private GeminiBlob inlineData;

    @JsonProperty("fileData")
    private GeminiFileData fileData;

    @JsonProperty("thought")
    private Boolean thought;

    @JsonProperty("signature")
    private String signature;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public GeminiFunctionCall getFunctionCall() {
        return functionCall;
    }

    public void setFunctionCall(GeminiFunctionCall functionCall) {
        this.functionCall = functionCall;
    }

    public GeminiFunctionResponse getFunctionResponse() {
        return functionResponse;
    }

    public void setFunctionResponse(GeminiFunctionResponse functionResponse) {
        this.functionResponse = functionResponse;
    }

    public GeminiBlob getInlineData() {
        return inlineData;
    }

    public void setInlineData(GeminiBlob inlineData) {
        this.inlineData = inlineData;
    }

    public GeminiFileData getFileData() {
        return fileData;
    }

    public void setFileData(GeminiFileData fileData) {
        this.fileData = fileData;
    }

    public Boolean getThought() {
        return thought;
    }

    public void setThought(Boolean thought) {
        this.thought = thought;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    // Inner classes for Part content types

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiFunctionCall {
        @JsonProperty("id")
        private String id; // Added ID field

        @JsonProperty("name")
        private String name;

        @JsonProperty("args")
        private Map<String, Object> args;

        public GeminiFunctionCall() {}

        public GeminiFunctionCall(String name, Map<String, Object> args) {
            this.name = name;
            this.args = args;
        }

        public GeminiFunctionCall(String id, String name, Map<String, Object> args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getArgs() {
            return args;
        }

        public void setArgs(Map<String, Object> args) {
            this.args = args;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiFunctionResponse {
        @JsonProperty("id")
        private String id; // Added ID field

        @JsonProperty("name")
        private String name;

        @JsonProperty("response")
        private Map<String, Object> response;

        public GeminiFunctionResponse() {}

        public GeminiFunctionResponse(String name, Map<String, Object> response) {
            this.name = name;
            this.response = response;
        }

        public GeminiFunctionResponse(String id, String name, Map<String, Object> response) {
            this.id = id;
            this.name = name;
            this.response = response;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getResponse() {
            return response;
        }

        public void setResponse(Map<String, Object> response) {
            this.response = response;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiBlob {
        @JsonProperty("mimeType")
        private String mimeType;

        @JsonProperty("data")
        private String data; // Base64 string

        public GeminiBlob() {}

        public GeminiBlob(String mimeType, String data) {
            this.mimeType = mimeType;
            this.data = data;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GeminiFileData {
        @JsonProperty("mimeType")
        private String mimeType;

        @JsonProperty("fileUri")
        private String fileUri;

        public GeminiFileData() {}

        public GeminiFileData(String mimeType, String fileUri) {
            this.mimeType = mimeType;
            this.fileUri = fileUri;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public String getFileUri() {
            return fileUri;
        }

        public void setFileUri(String fileUri) {
            this.fileUri = fileUri;
        }
    }
}
