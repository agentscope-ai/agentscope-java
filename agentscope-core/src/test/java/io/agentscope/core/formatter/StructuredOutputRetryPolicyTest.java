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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredOutputRetryPolicyTest {

    @Test
    void defaultsMatchFrameworkConvention() {
        StructuredOutputRetryPolicy policy = StructuredOutputRetryPolicy.defaults();
        assertEquals(3, policy.maxAttempts());
        assertNull(policy.tokenBudget()); // unlimited by default: guard exists but opt-in
        assertNull(policy.onFailedAttempt());
    }

    @Test
    void builderRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputRetryPolicy.builder().maxAttempts(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputRetryPolicy.builder().tokenBudget(0L));
    }

    @Test
    void nullValidationErrorListIsNormalizedToEmpty() {
        FailedAttempt attempt =
                new FailedAttempt(
                        1, FailedAttempt.Kind.VALIDATION_ERROR, null, null, "{}", 10L, 5L);
        assertEquals(List.of(), attempt.validationErrors());
        assertEquals(15L, attempt.totalTokens().orElseThrow());
    }
}
