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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests the complete ReAct path for a length-limited incomplete tool call. */
class ReActAgentLengthLimitedToolCallTest {

    /** Returns scripted streaming responses in order for the truncated-call end-to-end test. */
    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            int currentIndex = index.getAndIncrement();
            if (currentIndex >= scripts.size()) {
                return Flux.just(ChatResponse.builder().content(List.of()).build());
            }
            return scripts.get(currentIndex).get();
        }
    }

    /** 记录调用次数，确保截断参数在到达工具实现前被拦截。 */
    private static final class CountingTool implements AgentTool {
        private final AtomicInteger invocations;

        private CountingTool(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @Override
        public String getName() {
            return "write_file";
        }

        @Override
        public String getDescription() {
            return "Counts executions for the truncated argument test";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invocations.incrementAndGet();
            return Mono.just(ToolResultBlock.text("should-not-run"));
        }
    }

    /** 验证长度截断的工具调用不会执行且会补齐同 ID 的失败结果。 */
    @Test
    void lengthLimitedIncompleteToolCallProducesErrorResultWithoutExecutingTool() {
        AtomicInteger invocations = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new CountingTool(invocations));

        ChatResponse partialToolCall =
                ChatResponse.builder()
                        .id("response-1")
                        .content(
                                List.<ContentBlock>of(
                                        ToolUseBlock.builder()
                                                .id("call-truncated")
                                                .name("write_file")
                                                .input(Map.of("path", "index.html"))
                                                .content("{\"path\":\"index.html\",\"content\":\"")
                                                .build()))
                        .build();
        ChatResponse lengthLimitedEnd =
                ChatResponse.builder()
                        .id("response-1")
                        .content(List.of())
                        .finishReason("length")
                        .build();
        ChatResponse completion =
                ChatResponse.builder()
                        .id("response-2")
                        .content(List.of(TextBlock.builder().text("recovered").build()))
                        .finishReason("stop")
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(
                                new ScriptedModel(
                                        List.of(
                                                () -> Flux.just(partialToolCall, lengthLimitedEnd),
                                                () -> Flux.just(completion))))
                        .toolkit(toolkit)
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        assertEquals(0, invocations.get());
        assertTrue(
                events.stream()
                        .filter(ModelCallEndEvent.class::isInstance)
                        .map(ModelCallEndEvent.class::cast)
                        .anyMatch(event -> "length".equals(event.getFinishReason())));

        ToolResultBlock result =
                agent.getAgentState().getContext().stream()
                        .flatMap(
                                message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .filter(toolResult -> "call-truncated".equals(toolResult.getId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("write_file", result.getName());
        assertTrue(
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getText()
                        .contains("Do not retry this call unchanged"));
    }
}
