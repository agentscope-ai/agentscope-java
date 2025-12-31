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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI image URL DTO.
 *
 * <p>This class represents an image URL in the OpenAI API format.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "url": "https://example.com/image.jpg",
 *   "detail": "high"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIImageUrl {

    /** The URL of the image, or a base64-encoded data URI. */
    @JsonProperty("url")
    private String url;

    /**
     * The detail level for image processing.
     * Values: "low", "high", or "auto" (default).
     */
    @JsonProperty("detail")
    private String detail;

    public OpenAIImageUrl() {}

    public OpenAIImageUrl(String url) {
        this.url = url;
    }

    public OpenAIImageUrl(String url, String detail) {
        this.url = url;
        this.detail = detail;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
