/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.model.tts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope TTS API HTTP response.
 *
 * <p>Response format:
 * <pre>{@code
 * {
 *   "request_id": "xxx",
 *   "output": {
 *     "audio": {
 *       "url": "https://...",
 *       "data": "base64_encoded_audio"
 *     }
 *   },
 *   "usage": {...}
 * }
 * }</pre>
 */
public class DashScopeTTSResponse {

    @JsonProperty("code")
    private final String code;

    @JsonProperty("message")
    private final String message;

    @JsonProperty("request_id")
    private final String requestId;

    @JsonProperty("output")
    private final Output output;

    @JsonCreator
    public DashScopeTTSResponse(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("output") Output output) {
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.output = output;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }

    public Output getOutput() {
        return output;
    }

    /**
     * Output section of the response.
     */
    public static class Output {
        @JsonProperty("audio")
        private final Audio audio;

        @JsonCreator
        public Output(@JsonProperty("audio") Audio audio) {
            this.audio = audio;
        }

        public Audio getAudio() {
            return audio;
        }
    }

    /**
     * Audio section of the output.
     */
    public static class Audio {
        @JsonProperty("url")
        private final String url;

        @JsonProperty("data")
        private final String data;

        @JsonCreator
        public Audio(@JsonProperty("url") String url, @JsonProperty("data") String data) {
            this.url = url;
            this.data = data;
        }

        public String getUrl() {
            return url;
        }

        public String getData() {
            return data;
        }
    }
}
