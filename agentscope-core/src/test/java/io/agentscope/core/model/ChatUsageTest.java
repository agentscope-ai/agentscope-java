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
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ChatUsageTest {

    @Test
    void plusSumsEveryField() {
        ChatUsage a = new ChatUsage(10, 2, 3, 4, 1, 5, 0.5);
        ChatUsage b = new ChatUsage(15, 3, 6, 1, 2, 7, 0.25);

        ChatUsage sum = a.plus(b);

        assertEquals(25, sum.getInputTokens());
        assertEquals(5, sum.getOutputTokens());
        assertEquals(9, sum.getCachedTokens());
        assertEquals(5, sum.getCacheCreationTokens());
        assertEquals(3, sum.getReasoningTokens());
        assertEquals(12, sum.getToolUsePromptTokens());
        assertEquals(0.75, sum.getTime(), 1e-9);
        assertEquals(30, sum.getTotalTokens());
        assertEquals(10, a.getInputTokens(), "operands are not modified");
    }

    @Test
    void plusNullReturnsSameInstance() {
        ChatUsage a = new ChatUsage(10, 2, 0.5);

        assertSame(a, a.plus(null));
    }
}
