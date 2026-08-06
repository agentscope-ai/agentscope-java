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

/** Typed HTTP / session.error categories shared across the Managed Agents API surface. */
public enum ApiErrorType {
    INVALID_REQUEST("invalid_request_error", HttpStatus.BAD_REQUEST),
    AUTHENTICATION("authentication_error", HttpStatus.UNAUTHORIZED),
    PERMISSION("permission_error", HttpStatus.FORBIDDEN),
    NOT_FOUND("not_found_error", HttpStatus.NOT_FOUND),
    CONFLICT("conflict_error", HttpStatus.CONFLICT),
    RATE_LIMIT("rate_limit_error", HttpStatus.TOO_MANY_REQUESTS),
    API("api_error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String wireName;
    private final HttpStatus httpStatus;

    ApiErrorType(String wireName, HttpStatus httpStatus) {
        this.wireName = wireName;
        this.httpStatus = httpStatus;
    }

    public String wireName() {
        return wireName;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** Maps an HTTP status to the closest typed error category. */
    public static ApiErrorType fromHttpStatus(HttpStatus status) {
        if (status == null) {
            return API;
        }
        return switch (status.value()) {
            case 400 -> INVALID_REQUEST;
            case 401 -> AUTHENTICATION;
            case 403 -> PERMISSION;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 429 -> RATE_LIMIT;
            default -> status.is4xxClientError() ? INVALID_REQUEST : API;
        };
    }
}
