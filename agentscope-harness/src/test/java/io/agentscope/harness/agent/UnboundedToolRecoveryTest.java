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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class UnboundedToolRecoveryTest {
    @TempDir Path workspace;

    public static class ExternalTool {
        final AtomicInteger calls = new AtomicInteger();
        int repetitions;

        @Tool(name = "external_snapshot", description = "Read external evidence", readOnly = true)
        public String snapshot() {
            calls.incrementAndGet();
            return "evidence ".repeat(repetitions);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,true,0",
        "true,false,0",
        "false,true,0",
        "false,false,0",
        "true,true,16000",
        "false,true,16000"
    })
    void survivesOversizedResultNextMessageAndReload(
            boolean legacy, boolean streaming, int window) {
        ExternalTool tool = new ExternalTool();
        tool.repetitions = window == 0 ? 7_000 : 70_000;
        AtomicInteger overflows = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "budget-probe";
                    }

                    public int getContextWindowSize() {
                        return window;
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
                        // Synthetic provider limit: independent of the framework's
                        // estimator/window.
                        if (JsonUtils.getJsonCodec().toJson(messages).length() > 55_000) {
                            overflows.incrementAndGet();
                            return Flux.error(new IllegalStateException("context_length_exceeded"));
                        }
                        if (tool.calls.get() == 0) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("snapshot-call")
                                                                    .name("external_snapshot")
                                                                    .input(Map.of())
                                                                    .content("{}")
                                                                    .build()))
                                            .finishReason("tool_calls")
                                            .build());
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("OK").build()))
                                        .finishReason("stop")
                                        .build());
                    }
                };
        for (int restart = 0; restart < 2; restart++) {
            try (HarnessAgent agent =
                    HarnessAgent.builder()
                            .name("budget-probe")
                            .agentId("budget-probe")
                            .model(model)
                            .workspace(workspace)
                            .legacySessionHistory(legacy)
                            .stateStore(new JsonFileAgentStateStore(workspace.resolve("legacy")))
                            .disableMemoryHooks()
                            .disableMemoryTools()
                            .disableToolResultEviction()
                            .compaction(
                                    CompactionConfig.builder().flushBeforeCompact(false).build())
                            .build()) {
                agent.getToolkit().registerTool(tool);
                var rc = RuntimeContext.builder().userId("test").sessionId("same-session").build();
                for (int turn = 0; turn < 2; turn++) {
                    Msg question = Msg.builder().role(MsgRole.USER).textContent("continue").build();
                    if (streaming)
                        assertTrue(
                                !agent.streamEvents(question, rc)
                                        .collectList()
                                        .block(Duration.ofSeconds(30))
                                        .isEmpty());
                    else
                        assertEquals(
                                "OK",
                                agent.call(question, rc)
                                        .block(Duration.ofSeconds(30))
                                        .getTextContent());
                }
            }
        }
        assertEquals(1, tool.calls.get(), "Recovery/reload must not reexecute the tool");
        assertTrue(overflows.get() <= 1, "Bounded recovery must not loop across new messages");
        if (window == 0)
            assertEquals(
                    1, overflows.get(), "Unknown window must exercise provider rejection recovery");
    }
}
