/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.a2a.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.a2a.server.auth.A2aAuthException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps A2A admission failures to real HTTP responses before JSON-RPC dispatch. */
@RestControllerAdvice(assignableTypes = A2aJsonRpcController.class)
public class A2aAuthExceptionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ExceptionHandler(A2aAuthException.class)
    public void handle(A2aAuthException error, HttpServletResponse response) throws IOException {
        response.setStatus(error.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (error.getHttpStatus() == 401) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        OBJECT_MAPPER.writeValue(
                response.getWriter(),
                new A2aAuthErrorResponse(
                        error.getHttpStatus(),
                        error.getCode(),
                        error.getMessage(),
                        Instant.now().toString()));
    }

    public record A2aAuthErrorResponse(int status, String code, String message, String timestamp) {}
}
