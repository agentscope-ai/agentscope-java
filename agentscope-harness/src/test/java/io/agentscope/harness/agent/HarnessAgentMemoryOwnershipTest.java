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

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HarnessAgentMemoryOwnershipTest {
    @TempDir Path workspace;

    @Test
    void closingAgentDoesNotWaitForUnrelatedBackgroundWork() {
        HarnessAgent agent =
                HarnessAgent.builder().name("independent").workspace(workspace).build();
        MemoryBackgroundTasks.begin();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), agent::close);
        } finally {
            MemoryBackgroundTasks.end();
        }
    }

    @Test
    void closingAgentWaitsForItsOwnBackgroundWork() throws Exception {
        HarnessAgent agent = HarnessAgent.builder().name("owner").workspace(workspace).build();
        MemoryBackgroundTasks.begin(agent.getDelegate());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var closed = executor.submit(agent::close);
            Assertions.assertThrows(
                    TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            MemoryBackgroundTasks.end(agent.getDelegate());
            closed.get(5, TimeUnit.SECONDS);
        } finally {
            MemoryBackgroundTasks.end(agent.getDelegate());
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedHarnessProviderUsesRequestedChildAndUserContext(boolean streaming) {
        var messages = new AtomicReference<List<Msg>>();
        Model model =
                new Model() {
                    @Override
                    public reactor.core.publisher.Flux<ChatResponse> stream(
                            List<Msg> input, List<ToolSchema> tools, GenerateOptions options) {
                        messages.set(input);
                        return reactor.core.publisher.Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("OK").build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "local-fixture";
                    }
                };
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-harness")
                        .workspace(workspace)
                        .model(model)
                        .disableMemoryHooks()
                        .build()) {
            var tool =
                    new SubAgentTool(
                            () -> agent, SubAgentConfig.builder().forwardEvents(streaming).build());
            for (String user : List.of("alice", "bob")) {
                tool.callAsync(
                                ToolCallParam.builder()
                                        .input(
                                                Map.of(
                                                        "message",
                                                        user + " secret",
                                                        "session_id",
                                                        "child"))
                                        .runtimeContext(
                                                RuntimeContext.builder()
                                                        .userId(user)
                                                        .sessionId("parent")
                                                        .build())
                                        .emitter(chunk -> {})
                                        .build())
                        .block();
            }
            Assertions.assertEquals(
                    List.of("bob secret"),
                    messages.get().stream()
                            .filter(m -> m.getRole() == MsgRole.USER)
                            .filter(m -> m.getTextContent().endsWith("secret"))
                            .map(Msg::getTextContent)
                            .toList());
        }
    }
}
