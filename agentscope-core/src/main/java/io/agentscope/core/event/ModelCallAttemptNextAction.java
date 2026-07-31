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
 * Describes the next action the system will take after a model call attempt failure.
 */
public enum ModelCallAttemptNextAction {
    /** The system will retry the same model. */
    RETRY("retry"),
    /** The system will switch to the fallback model. */
    FALLBACK("fallback"),
    /** The system will propagate the failure (no more retries or fallback). */
    FAIL("fail");

    private final String value;

    ModelCallAttemptNextAction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
