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
package io.agentscope.core.a2a.server.auth;

/** Authentication or trusted-user binding failure that must remain an HTTP error. */
public class A2aAuthException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public A2aAuthException(int httpStatus, String code) {
        this(httpStatus, code, code, null);
    }

    public A2aAuthException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, null);
    }

    public A2aAuthException(int httpStatus, String code, String message, Throwable cause) {
        super(requireText(message, "message"), cause);
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be an HTTP error");
        }
        this.httpStatus = httpStatus;
        this.code = requireText(code, "code");
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
