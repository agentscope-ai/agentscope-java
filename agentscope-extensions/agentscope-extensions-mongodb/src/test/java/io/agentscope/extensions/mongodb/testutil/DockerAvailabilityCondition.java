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
package io.agentscope.extensions.mongodb.testutil;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 {@link ExecutionCondition} that disables the annotated test class when Docker is not
 * available.
 *
 * <p>This prevents {@code @Testcontainers} from attempting to start containers in environments
 * without a Docker daemon (e.g. GitHub-hosted Windows runners), where the container start would
 * fail with {@code IllegalStateException: Could not find a valid Docker environment}.
 *
 * <p>Used via the {@link RequireDocker @RequireDocker} composed annotation.
 */
class DockerAvailabilityCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");
    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("Docker is not available; skipping Testcontainers");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        if (testClass.isEmpty()) {
            return ENABLED;
        }

        boolean requireDocker =
                testClass.get().getAnnotation(RequireDocker.class) != null
                        || context.getTestMethod()
                                .map(Method::getDeclaringClass)
                                .map(c -> c.getAnnotation(RequireDocker.class) != null)
                                .orElse(false);

        if (!requireDocker) {
            return ENABLED;
        }

        try {
            return DockerClientFactory.instance().isDockerAvailable() ? ENABLED : DISABLED;
        } catch (Exception e) {
            return DISABLED;
        }
    }
}
