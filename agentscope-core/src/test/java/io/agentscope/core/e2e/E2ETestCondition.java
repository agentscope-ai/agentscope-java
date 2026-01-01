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
 * <p>E2E tests are only enabled when at least one API key (OPENAI_API_KEY or DASHSCOPE_API_KEY)
 * is available. This prevents tests from failing when no API keys are configured.
 */
public class E2ETestCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (ProviderFactory.hasAnyApiKey()) {
            return ConditionEvaluationResult.enabled(
                    "E2E tests enabled. Available API keys: " + ProviderFactory.getApiKeyStatus());
        } else {
            return ConditionEvaluationResult.disabled(
                    "E2E tests disabled. No API keys configured. Set OPENAI_API_KEY and/or"
                            + " DASHSCOPE_API_KEY environment variables to enable E2E tests.");
        }
    }
}
