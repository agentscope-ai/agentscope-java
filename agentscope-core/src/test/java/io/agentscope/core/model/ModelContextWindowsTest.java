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

import org.junit.jupiter.api.Test;

class ModelContextWindowsTest {

    @Test
    void anthropicFiveSeriesModelsHaveOneMillionTokenWindow() {
        for (String name :
                new String[] {
                    "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1"
                }) {
            assertEquals(
                    1_000_000,
                    ModelContextWindows.lookup(name, ModelContextWindows.ANTHROPIC),
                    name);
        }
    }

    @Test
    void anthropicFourSeriesModelsKeepTwoHundredThousandTokenWindow() {
        for (String name :
                new String[] {"claude-opus-4-5", "claude-haiku-4-5", "claude-sonnet-4-5"}) {
            assertEquals(
                    200_000, ModelContextWindows.lookup(name, ModelContextWindows.ANTHROPIC), name);
        }
    }
}
