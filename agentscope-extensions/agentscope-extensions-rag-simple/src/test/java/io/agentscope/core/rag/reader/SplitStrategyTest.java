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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SplitStrategy enum.
 */
@Tag("unit")
@DisplayName("SplitStrategy Unit Tests")
class SplitStrategyTest {

    @Test
    @DisplayName("Should have all expected enum values")
    void testEnumValues() {
        assertNotNull(SplitStrategy.CHARACTER);
        assertNotNull(SplitStrategy.PARAGRAPH);
        assertNotNull(SplitStrategy.TOKEN);
        assertNotNull(SplitStrategy.SEMANTIC);
    }

    @Test
    @DisplayName("Should be usable in switch expressions")
    void testSwitchExpression() {
        SplitStrategy strategy = SplitStrategy.CHARACTER;
        String result =
                switch (strategy) {
                    case CHARACTER -> "char";
                    case PARAGRAPH -> "para";
                    case TOKEN -> "token";
                    case SEMANTIC -> "semantic";
                };
        assertNotNull(result);
    }
}
