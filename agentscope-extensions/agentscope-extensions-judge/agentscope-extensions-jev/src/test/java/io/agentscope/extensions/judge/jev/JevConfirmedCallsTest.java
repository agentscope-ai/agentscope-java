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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevConfirmedCallsTest {
    @Test
    void bindsParametersAtPromptTimeNotAtConfirmationReturn() {
        var ctx = RuntimeContext.empty();
        var nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("amount", 1);
        var call = new ToolUseBlock("id", "refund", Map.of("nested", nested));
        var snapshot = JevConfirmedCalls.snapshot(call);
        nested.put("amount", 100);
        JevConfirmedCalls.remember(ctx, snapshot);
        assertFalse(JevConfirmedCalls.matches(ctx, call));
    }

    @Test
    void bindsExactArgumentsAndContext() {
        var ctx = RuntimeContext.empty();
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("amount", 1);
        var call = new ToolUseBlock("id", "refund", Map.of("nested", args));
        JevConfirmedCalls.remember(ctx, call);
        assertTrue(JevConfirmedCalls.matches(ctx, call));
        assertFalse(JevConfirmedCalls.matches(RuntimeContext.empty(), call));
        args.put("amount", 100);
        assertFalse(JevConfirmedCalls.matches(ctx, call));
        assertFalse(JevConfirmedCalls.matches(ctx, new ToolUseBlock("id", "send", Map.of())));
    }
}
