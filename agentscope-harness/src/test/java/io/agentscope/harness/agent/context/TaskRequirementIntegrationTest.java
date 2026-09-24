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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class TaskRequirementIntegrationTest {
    @TempDir Path workspace;
    @TempDir Path storeDirectory;

    @Test
    void proposalBecomesCandidateInActualModelRequestAndSurvivesReload() {
        var calls = new AtomicInteger();
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        Map<String, Object> arguments =
                Map.of(
                        "kind",
                        "CONSTRAINT",
                        "text",
                        "no-production-deploy",
                        "source_ref",
                        "message:u1");
        var use =
                ToolUseBlock.builder()
                        .id("requirement-call")
                        .name("task_requirement_propose")
                        .input(arguments)
                        .content(JsonUtils.getJsonCodec().toJson(arguments))
                        .build();
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            int index = calls.getAndIncrement();
                            if (index == 0)
                                return Flux.just(
                                        new ChatResponse(
                                                "proposal",
                                                List.of(use),
                                                null,
                                                Map.of(),
                                                "tool_use"));
                            List<Msg> messages = invocation.getArgument(0);
                            assertTrue(
                                    messages.stream()
                                            .filter(message -> message.getRole() == MsgRole.USER)
                                            .anyMatch(
                                                    message ->
                                                            message.getTextContent()
                                                                    .contains(
                                                                            "CONSTRAINT | CANDIDATE"
                                                                                + " | no-production-deploy")));
                            assertTrue(
                                    messages.stream()
                                            .filter(message -> message.getRole() == MsgRole.SYSTEM)
                                            .noneMatch(
                                                    message ->
                                                            message.getTextContent()
                                                                    .contains(
                                                                            "no-production-deploy")));
                            return Flux.just(
                                    new ChatResponse(
                                            "reply",
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("Proposal recorded")
                                                            .build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        for (int i = 0; i < 2; i++) {
            try (var agent =
                    HarnessAgent.builder()
                            .name("requirements")
                            .agentId("requirements")
                            .model(model)
                            .workspace(workspace)
                            .enableTaskList()
                            .taskContext(
                                    TaskContextOptions.builder()
                                            .allowRequirementProposals()
                                            .includeVerificationResults()
                                            .build())
                            .memory(
                                    MemoryConfig.builder()
                                            .flushTrigger(MemoryConfig.FlushTrigger.never())
                                            .build())
                            .stateStore(new JsonFileAgentStateStore(storeDirectory))
                            .build()) {
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("continue")
                                                .build()),
                                context)
                        .collectList()
                        .block(Duration.ofSeconds(20));
            }
        }
        assertEquals(3, calls.get());
    }
}
