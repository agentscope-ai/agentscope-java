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
package io.agentscope.core.rag.integration.haystack.exception;

/**
 * Exception thrown when HayStack API call fails.
 */
public class HayStackApiException extends RuntimeException {

    private final int statusCode;

    private final String errorCode;

    public HayStackApiException(String message) {
        super(message);
        this.statusCode = -1;
        this.errorCode = null;
    }

    public HayStackApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    public HayStackApiException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public HayStackApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.errorCode = null;
    }

    public HayStackApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "HayStackApiException{"
                + "statusCode="
                + statusCode
                + ", errorCode='"
                + errorCode
                + '\''
                + ", message='"
                + getMessage()
                + '\''
                + '}';
    }
}
