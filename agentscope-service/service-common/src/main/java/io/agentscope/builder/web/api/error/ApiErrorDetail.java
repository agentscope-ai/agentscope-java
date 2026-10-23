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
package io.agentscope.builder.web.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared typed error object for HTTP responses and {@code session.error} payloads. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorDetail(
        String type,
        String code,
        String message,
        String param,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("retry_status") String retryStatus) {

    public static ApiErrorDetail of(ApiErrorType type, String code, String message) {
        return new ApiErrorDetail(type.wireName(), code, message, null, null, null);
    }

    public ApiErrorDetail withParam(String param) {
        return new ApiErrorDetail(type, code, message, param, sessionId, retryStatus);
    }

    public ApiErrorDetail withSessionId(String sessionId) {
        return new ApiErrorDetail(type, code, message, param, sessionId, retryStatus);
    }

    public ApiErrorDetail withRetryStatus(String retryStatus) {
        return new ApiErrorDetail(type, code, message, param, sessionId, retryStatus);
    }

    /** Flattens into a payload map suitable for {@code session.error}. */
    public Map<String, Object> toMap() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("code", code);
        error.put("message", message);
        if (param != null) {
            error.put("param", param);
        }
        if (sessionId != null) {
            error.put("session_id", sessionId);
        }
        if (retryStatus != null) {
            error.put("retry_status", retryStatus);
        }
        return error;
    }
}
