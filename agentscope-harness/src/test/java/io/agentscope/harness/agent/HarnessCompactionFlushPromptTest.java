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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class HarnessCompactionFlushPromptTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @CsvSource({
        "false, false, true, true", "false, true, true, true",
        "true, false, true, true", "true, true, true, true",
        "false, false, false, true", "false, true, false, true",
        "true, false, false, true", "true, true, false, true",
        "false, false, true, false", "false, true, true, false",
        "true, false, true, false", "true, true, true, false"
    })
    void usesConfiguredFlushPrompt(
            boolean overflow, boolean streaming, boolean customPrompt, boolean flushBeforeCompact) {
        String prompt =
                customPrompt ? "Remember only project decisions. CUSTOM_FLUSH_MARKER" : null;
        RecordingModel auxiliary = new RecordingModel(false);
        RecordingModel reasoning = new RecordingModel(overflow);
        MemoryConfig memory =
                MemoryConfig.builder()
                        .flushPrompt(prompt)
                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                        .build();
        CompactionConfig compaction =
                CompactionConfig.builder()
                        .model(auxiliary)
                        .triggerMessages(overflow ? 100 : 3)
                        .flushBeforeCompact(flushBeforeCompact)
                        .triggerTokens(100000)
                        .keepMessages(1)
                        .keepTokens(0)
                        .build();
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("flush-prompt-test")
                        .model(reasoning)
                        .workspace(workspace)
                        .memory(memory)
                        .compaction(compaction)
                        .disableSubagents()
                        .build()) {
            List<Msg> history =
                    List.of(
                            message(MsgRole.USER, "We decided to use Java."),
                            message(MsgRole.ASSISTANT, "Decision recorded."),
                            message(MsgRole.USER, "What did we decide?"));
            RuntimeContext ctx = RuntimeContext.builder().sessionId("prompt-session").build();
            if (streaming) {
                agent.streamEvents(history, ctx).collectList().block(Duration.ofSeconds(20));
            } else {
                assertEquals(
                        "answer",
                        agent.call(history, ctx).block(Duration.ofSeconds(20)).getTextContent());
            }
        }
        assertEquals(overflow ? 2 : 1, reasoning.calls.get());
        assertEquals(flushBeforeCompact ? 2 : 1, auxiliary.inputs.size());
        if (flushBeforeCompact) {
            Msg extractionPrompt = auxiliary.inputs.get(0).get(0);
            assertEquals(MsgRole.SYSTEM, extractionPrompt.getRole());
            assertEquals(
                    prompt == null ? MemoryFlushManager.DEFAULT_FLUSH_PROMPT : prompt,
                    extractionPrompt.getTextContent());
        }
        assertEquals(
                MsgRole.USER,
                auxiliary.inputs.get(auxiliary.inputs.size() - 1).get(0).getRole(),
                "summary input must remain separate from the extraction prompt");
    }

    @Test
    void existingConstructorKeepsDefaultFlushPrompt() {
        RecordingModel model = new RecordingModel(false);
        CompactionConfig config =
                CompactionConfig.builder().triggerMessages(2).keepMessages(1).keepTokens(0).build();
        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            CompactionMiddleware middleware = new CompactionMiddleware(manager, model, config);
            var agent = ReActAgent.builder().name("direct-compaction").model(model).build();
            var input =
                    new ReasoningInput(
                            List.of(
                                    message(MsgRole.USER, "Remember Java."),
                                    message(MsgRole.USER, "Continue.")),
                            List.of(),
                            null);
            middleware
                    .onReasoning(agent, RuntimeContext.empty(), input, next -> Flux.empty())
                    .then()
                    .block(Duration.ofSeconds(10));
        }
        assertEquals(2, model.inputs.size());
        assertEquals(
                MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                model.inputs.get(0).get(0).getTextContent());
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private static final class RecordingModel extends ChatModelBase {
        private final boolean overflow;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        private RecordingModel(boolean overflow) {
            this.overflow = overflow;
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0 && overflow) {
                return Flux.error(new IllegalStateException("context_length_exceeded"));
            }
            // Memory extraction has a SYSTEM message followed by the conversation to extract.
            // Return NO_REPLY so no background consolidation is needed in this test.
            String text =
                    messages.size() == 2
                                    && messages.get(0).getRole() == MsgRole.SYSTEM
                                    && messages.get(1)
                                            .getTextContent()
                                            .startsWith("Extract NEW memories")
                            ? "NO_REPLY"
                            : "answer";
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(text).build()))
                            .build());
        }
    }
}
