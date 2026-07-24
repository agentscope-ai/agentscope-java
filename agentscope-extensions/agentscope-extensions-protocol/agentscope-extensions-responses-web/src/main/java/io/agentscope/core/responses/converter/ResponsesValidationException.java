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
package io.agentscope.core.responses.converter;

/** Validation exception carrying Responses-style error metadata. */
public class ResponsesValidationException extends IllegalArgumentException {

    private final String param;
    private final String code;

    public ResponsesValidationException(String message, String param, String code) {
        super(message);
        this.param = param;
        this.code = code;
    }

    /**
     * Create an {@code invalid_request} exception for malformed request data.
     *
     * @param message Human-readable error message
     * @param param Request parameter path associated with the error
     * @return Validation exception carrying Responses-style metadata
     */
    public static ResponsesValidationException invalid(String message, String param) {
        return new ResponsesValidationException(message, param, "invalid_request");
    }

    /**
     * Create an {@code unsupported_parameter} exception for valid shapes that are not mapped yet.
     *
     * @param message Human-readable error message
     * @param param Request parameter path associated with the error
     * @return Validation exception carrying Responses-style metadata
     */
    public static ResponsesValidationException unsupported(String message, String param) {
        return new ResponsesValidationException(message, param, "unsupported_parameter");
    }

    public String getParam() {
        return param;
    }

    public String getCode() {
        return code;
    }
}
