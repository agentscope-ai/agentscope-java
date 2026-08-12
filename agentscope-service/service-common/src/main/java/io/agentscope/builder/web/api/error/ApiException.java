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

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Typed API failure with stable machine {@code code} for HTTP and session.error payloads. */
public class ApiException extends ResponseStatusException {

    private final ApiErrorType errorType;
    private final String code;
    private final String param;
    private final String sessionId;

    public ApiException(ApiErrorType errorType, String code, String message) {
        this(errorType, code, message, null, null);
    }

    public ApiException(
            ApiErrorType errorType, String code, String message, String param, String sessionId) {
        super(errorType.httpStatus(), message);
        this.errorType = errorType;
        this.code = code;
        this.param = param;
        this.sessionId = sessionId;
    }

    public ApiErrorType errorType() {
        return errorType;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }

    public String sessionId() {
        return sessionId;
    }

    public ApiErrorDetail toDetail() {
        return new ApiErrorDetail(errorType.wireName(), code, getReason(), param, sessionId, null);
    }

    public static ApiException invalidRequest(String code, String message) {
        return new ApiException(ApiErrorType.INVALID_REQUEST, code, message);
    }

    public static ApiException invalidRequest(String code, String message, String param) {
        return new ApiException(ApiErrorType.INVALID_REQUEST, code, message, param, null);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(ApiErrorType.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(ApiErrorType.CONFLICT, code, message);
    }

    public static ApiException permission(String code, String message) {
        return new ApiException(ApiErrorType.PERMISSION, code, message);
    }

    public static ApiException authentication(String code, String message) {
        return new ApiException(ApiErrorType.AUTHENTICATION, code, message);
    }

    public static ApiException api(String code, String message) {
        return new ApiException(ApiErrorType.API, code, message);
    }

    /** Best-effort wrap of a bare {@link ResponseStatusException}. */
    public static ApiErrorDetail detailFrom(ResponseStatusException ex) {
        if (ex instanceof ApiException api) {
            return api.toDetail();
        }
        HttpStatus status =
                ex.getStatusCode() instanceof HttpStatus hs
                        ? hs
                        : HttpStatus.valueOf(ex.getStatusCode().value());
        ApiErrorType type = ApiErrorType.fromHttpStatus(status);
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return ApiErrorDetail.of(type, "http_" + status.value(), reason);
    }
}
