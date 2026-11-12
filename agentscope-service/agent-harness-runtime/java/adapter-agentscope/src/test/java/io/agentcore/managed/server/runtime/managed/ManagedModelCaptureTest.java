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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedModelCaptureTest {
    @Test
    void capturesOneSemanticResponseWithoutChunkHistory() {
        var capture =
                new ManagedModelCapture("fixture", "REASONING", List.of(), List.of(), null, "hash");
        capture.accept(
                ChatResponse.builder()
                        .id("reply")
                        .content(
                                List.of(
                                        TextBlock.builder().text("你").build(),
                                        ToolUseBlock.builder()
                                                .id("tool")
                                                .name("lookup")
                                                .content("{\"q\":")
                                                .build()))
                        .build());
        capture.accept(
                ChatResponse.builder()
                        .id("reply")
                        .content(
                                List.of(
                                        TextBlock.builder().text("好").build(),
                                        ToolUseBlock.builder()
                                                .id("tool")
                                                .name("__fragment__")
                                                .content("\"value\"}")
                                                .build()))
                        .usage(ChatUsage.builder().inputTokens(3).outputTokens(2).build())
                        .finishReason("tool_calls")
                        .build());
        var outcome = capture.freeze("completed");
        var responses = (List<?>) outcome.get("responses");
        assertEquals(1, responses.size());
        var response = (Map<?, ?>) responses.get(0);
        var blocks = (List<?>) response.get("content");
        assertTrue(
                blocks.stream()
                        .anyMatch(b -> b instanceof Map<?, ?> map && "你好".equals(map.get("text"))));
        assertTrue(
                blocks.stream()
                        .anyMatch(
                                b ->
                                        b instanceof Map<?, ?> map
                                                && map.get("input") instanceof Map<?, ?> args
                                                && "value".equals(args.get("q"))));
        assertEquals("tool_calls", response.get("finishReason"));
        assertEquals("reply", response.get("id"));
        assertTrue(((Map<?, ?>) response.get("usage")).get("outputTokens") instanceof Number);
    }

    @Test
    void failedPartialGenerationIsNotReplayable() {
        var capture =
                new ManagedModelCapture("fixture", "REASONING", List.of(), List.of(), null, "hash");
        capture.accept(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("partial").build()))
                        .build());
        assertEquals(List.of(), capture.freeze("failed").get("responses"));
        assertEquals(List.of(), capture.freeze("cancelled").get("responses"));
    }

    @Test
    void outputCaptureHasAnExplicitLimit() {
        var capture =
                new ManagedModelCapture("fixture", "REASONING", List.of(), List.of(), null, "hash");
        assertThrows(
                ManagedStateException.class,
                () ->
                        capture.accept(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("x".repeat(17 * 1024 * 1024))
                                                                .build()))
                                        .build()));
    }
}
