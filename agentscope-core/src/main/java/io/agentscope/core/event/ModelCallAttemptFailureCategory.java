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
package io.agentscope.core.event;

/**
 * Classifies the failure reason of a model call attempt.
 *
 * <p>Used by {@link ModelCallAttemptFailedEvent} to report structured failure information
 * without exposing raw error details or credentials.
 */
public enum ModelCallAttemptFailureCategory {
    RATE_LIMIT("rate_limit"),
    TIMEOUT("timeout"),
    AUTHENTICATION("authentication"),
    AUTHORIZATION("authorization"),
    NETWORK("network"),
    PROVIDER_5XX("provider_5xx"),
    INVALID_REQUEST("invalid_request"),
    UNKNOWN("unknown");

    private final String value;

    ModelCallAttemptFailureCategory(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
