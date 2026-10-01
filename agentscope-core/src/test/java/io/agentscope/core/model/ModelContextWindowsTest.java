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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link ModelContextWindows}. */
@Tag("unit")
@DisplayName("ModelContextWindows Unit Tests")
class ModelContextWindowsTest {

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    @DisplayName("Knows the window of the DeepSeek id the provider reports and of its aliases")
    void knowsDeepSeekModelIds(String modelName) {
        assertEquals(
                1_048_576, ModelContextWindows.lookup(modelName, ModelContextWindows.DEEPSEEK));
    }

    @Test
    @DisplayName("Matches a DeepSeek id by prefix, whatever its case")
    void matchesDeepSeekIdsByPrefixIgnoringCase() {
        assertEquals(
                1_048_576,
                ModelContextWindows.lookup("DeepSeek-Flash-2026", ModelContextWindows.DEEPSEEK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-chat", "deepseek-reasoner", "deepseek-v4", ""})
    @DisplayName("Returns 0 for a DeepSeek id the table does not key")
    void returnsZeroForUnknownDeepSeekIds(String modelName) {
        assertEquals(0, ModelContextWindows.lookup(modelName, ModelContextWindows.DEEPSEEK));
    }
}
