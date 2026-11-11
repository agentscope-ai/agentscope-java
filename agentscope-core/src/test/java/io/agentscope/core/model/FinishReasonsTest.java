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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the shared truncation interpretation of raw provider finish reasons. */
class FinishReasonsTest {

    @Test
    void recognizesExplicitOutputLimitFinishReasons() {
        assertTrue(FinishReasons.indicatesOutputLimit("length", Map.of()));
        assertTrue(FinishReasons.indicatesOutputLimit("MAX_TOKENS", Map.of()));
        assertTrue(FinishReasons.indicatesOutputLimit("max_tokens", Map.of()));
    }

    @Test
    void treatsOrdinaryFinishReasonsAsComplete() {
        assertFalse(FinishReasons.indicatesOutputLimit("stop", Map.of()));
        assertFalse(FinishReasons.indicatesOutputLimit("tool_calls", Map.of()));
        assertFalse(FinishReasons.indicatesOutputLimit(null, Map.of()));
        assertFalse(FinishReasons.indicatesOutputLimit("  ", Map.of()));
    }

    @Test
    void rejectsResponsesApiIncompleteOnlyForOutputTokenCause() {
        assertTrue(
                FinishReasons.indicatesOutputLimit(
                        "incomplete",
                        Map.of("openai.response.incomplete_reason", "max_output_tokens")));
        assertFalse(
                FinishReasons.indicatesOutputLimit(
                        "incomplete",
                        Map.of("openai.response.incomplete_reason", "content_filter")));
        assertFalse(FinishReasons.indicatesOutputLimit("incomplete", Map.of()));
        assertFalse(FinishReasons.indicatesOutputLimit("incomplete", null));
    }
}
