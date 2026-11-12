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
package io.agentcore.managed.server.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentBuildServiceMemoryTest {
    @TempDir Path root;

    @Test
    void defaultTurnsDoNotExtractOrMaintainMemories() {
        var model = model();
        try (var agent = agent(model)) {
            assertThat(agent.getDelegate().getMiddlewares())
                    .noneMatch(m -> m instanceof MemoryFlushMiddleware)
                    .noneMatch(m -> m instanceof MemoryMaintenanceMiddleware);
            for (int i = 0; i < 2; i++) {
                var reply =
                        agent.call(message(MsgRole.USER, "hello " + i), context())
                                .block(Duration.ofSeconds(10));
                assertThat(reply).isNotNull();
                assertThat(reply.getTextContent()).contains("done");
            }
            verify(model, times(2)).stream(anyList(), any(), any());
        }
    }

    @Test
    void contextCompactionStillSummarizesWithoutASeparateMemoryExtractionCall() {
        var model = model();
        try (var agent = agent(model)) {
            var history = new ArrayList<Msg>();
            for (int i = 0; i < 20; i++)
                history.add(
                        message(
                                i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT,
                                "long conversation " + i + " content".repeat(500)));
            var compacted =
                    agent.getCompactionHook()
                            .prepareCandidate(
                                    agent,
                                    context(),
                                    new ModelCallInput(
                                            history,
                                            List.of(),
                                            GenerateOptions.builder().build(),
                                            model),
                                    1000,
                                    history)
                            .block(Duration.ofSeconds(10));
            assertThat(compacted).isNotNull();
            assertThat(compacted.history().size()).isLessThan(history.size());
            assertThat(compacted.history().get(0).getTextContent()).contains("done");
            verify(model, times(1)).stream(anyList(), any(), any());
        }
    }

    private HarnessAgent agent(Model model) {
        return HarnessAgentBuildService.newAgentBuilder()
                .name("memory-disabled")
                .model(model)
                .workspace(root)
                .disableDefaultWorkspaceSkills()
                .build();
    }

    private Model model() {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build()));
        return model;
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("user").sessionId("session").build();
    }

    private Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }
}
