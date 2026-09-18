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
package io.agentscope.core.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 execution condition for E2E tests.
 *
 * <p>E2E tests require the ENABLE_E2E_TESTS environment variable to be set to "true", and at least
 * one API key (e.g., DASHSCOPE_API_KEY) to be available. This provides explicit control over E2E
 * test execution.
 *
 * <p>Usage:
 *
 * <ul>
 *   <li>{@code mvn test} - E2E tests disabled by default
 *   <li>{@code ENABLE_E2E_TESTS=true mvn test} - Enable E2E tests
 *   <li>{@code mvn test -DENABLE_E2E_TESTS=true} - Enable via system property
 * </ul>
 */
public class E2ETestCondition implements ExecutionCondition {

    /** Environment variable name to enable E2E tests. */
    public static final String ENABLE_E2E_ENV = "ENABLE_E2E_TESTS";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check if E2E tests are explicitly enabled
        String enableE2E = System.getenv(ENABLE_E2E_ENV);
        if (enableE2E == null || enableE2E.isEmpty()) {
            enableE2E = System.getProperty(ENABLE_E2E_ENV);
        }

        // If ENABLE_E2E_TESTS is not set or is false, disable
        if (enableE2E == null
                || enableE2E.isEmpty()
                || "false".equalsIgnoreCase(enableE2E)
                || "0".equals(enableE2E)) {
            return ConditionEvaluationResult.disabled(
                    "E2E tests disabled. Set ENABLE_E2E_TESTS=true to enable.");
        }

        // Check if any API key is available
        if (!ProviderFactory.hasAnyApiKey()) {
            return ConditionEvaluationResult.disabled(
                    "E2E tests enabled but no API keys configured. "
                            + "Set DASHSCOPE_API_KEY or other provider keys.");
        }

        return ConditionEvaluationResult.enabled(
                "E2E tests enabled. Available API keys: " + ProviderFactory.getApiKeyStatus());
    }
}
