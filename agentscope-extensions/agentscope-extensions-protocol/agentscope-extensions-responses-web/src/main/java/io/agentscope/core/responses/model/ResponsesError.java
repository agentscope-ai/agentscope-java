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
package io.agentscope.core.responses.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error payload used by Responses-compatible errors and failed responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesError {

    private String message;
    private String type;
    private String param;
    private String code;

    public ResponsesError() {}

    public ResponsesError(String message, String type, String param, String code) {
        this.message = message;
        this.type = type;
        this.param = param;
        this.code = code;
    }

    /**
     * Create the common Responses invalid-request error shape.
     *
     * @param message Human-readable error message
     * @param param Request parameter path associated with the error
     * @param code Error code
     * @return Error payload
     */
    public static ResponsesError invalidRequest(String message, String param, String code) {
        return new ResponsesError(message, "invalid_request_error", param, code);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getParam() {
        return param;
    }

    public void setParam(String param) {
        this.param = param;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
