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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@Tag("unit")
@DisplayName("ToolInvocation Unit Tests")
class ToolInvocationTest {

    private AgentTool noopTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "noop";
            }

            @Override
            public String getDescription() {
                return "no-op";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.empty();
            }
        };
    }

    @Test
    @DisplayName("Should tolerate null values and preserve JSON key order")
    void shouldTolerateNullValuesAndPreserveOrder() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("file_path", "a.txt");
        json.put("options", null);
        json.put("mode", "append");

        ToolInvocation invocation = new ToolInvocation("noop", noopTool(), json, null, null, null);

        // Null-tolerant copy, not Map.copyOf (which rejects nulls)
        assertTrue(invocation.input().containsKey("options"));
        assertNull(invocation.input().get("options"));
        // Iteration order matches the model JSON order
        assertEquals(
                List.of("file_path", "options", "mode"), List.copyOf(invocation.input().keySet()));
    }

    @Test
    @DisplayName("Should expose an immutable input view")
    void shouldExposeImmutableInput() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);
        ToolInvocation invocation =
                new ToolInvocation("noop", noopTool(), source, null, null, null);

        assertThrows(UnsupportedOperationException.class, () -> invocation.input().put("b", 2));
        // Mutating the source after construction must not leak through
        source.put("c", 3);
        assertTrue(!invocation.input().containsKey("c"));
    }

    @Test
    @DisplayName("Should normalize null and empty input to an empty map")
    void shouldNormalizeEmptyInput() {
        ToolInvocation nullInput = new ToolInvocation("noop", noopTool(), null, null, null, null);
        assertNotNull(nullInput.input());
        assertTrue(nullInput.input().isEmpty());

        ToolInvocation emptyInput =
                new ToolInvocation("noop", noopTool(), Map.of(), null, null, null);
        assertTrue(emptyInput.input().isEmpty());
    }

    @Test
    @DisplayName("withInput returns a new instance with replaced input")
    void withInputReplacesInput() {
        RuntimeContext ctx = RuntimeContext.empty();
        ToolInvocation original =
                new ToolInvocation("noop", noopTool(), Map.of("a", 1), ctx, "agent-1", "planner");

        Map<String, Object> rewritten = new HashMap<>();
        rewritten.put("a", 2);
        rewritten.put("b", null);
        ToolInvocation replaced = original.withInput(rewritten);

        assertEquals(1, original.input().get("a"));
        assertEquals(2, replaced.input().get("a"));
        assertEquals("noop", replaced.toolName());
        assertEquals(original.tool(), replaced.tool());
        assertEquals(ctx, replaced.runtimeContext());
        // Agent identity is part of the snapshot and survives rewrites
        assertEquals("agent-1", replaced.agentId());
        assertEquals("planner", replaced.agentName());
    }

    @Test
    @DisplayName("Should keep runtimeContext null when unavailable")
    void shouldKeepNullRuntimeContext() {
        ToolInvocation invocation =
                new ToolInvocation("noop", noopTool(), Map.of(), null, null, null);
        assertNull(invocation.runtimeContext());
        assertNull(invocation.agentId());
        assertNull(invocation.agentName());
    }
}
