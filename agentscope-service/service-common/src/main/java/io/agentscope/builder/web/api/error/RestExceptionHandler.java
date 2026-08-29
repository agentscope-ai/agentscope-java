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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/** Maps exceptions on {@code /api/**} to the shared typed error envelope. */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ApiErrorResponse.of(ex.toDetail()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException ex) {
        ApiErrorDetail detail = ApiException.detailFrom(ex);
        return ResponseEntity.status(ex.getStatusCode()).body(ApiErrorResponse.of(detail));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorResponse> handleInput(ServerWebInputException ex) {
        ApiErrorDetail detail =
                ApiErrorDetail.of(
                        ApiErrorType.INVALID_REQUEST,
                        "invalid_request_body",
                        ex.getReason() != null ? ex.getReason() : "Invalid request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleOther(Exception ex) {
        log.error("Unhandled API error", ex);
        ApiErrorDetail detail =
                ApiErrorDetail.of(
                        ApiErrorType.API,
                        "internal_error",
                        ex.getMessage() != null ? ex.getMessage() : "Internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(detail));
    }
}
