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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionRequestRecording;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;

class HarnessSessionRequestRecordingTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @EnumSource(SessionRequestRecording.class)
    void parentAndConfiguredChildUseTheirRecordingPolicy(SessionRequestRecording mode) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation ->
                                Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("answer")
                                                                        .build()))
                                                .build()));
        var parent = builder(model, "parent").sessionRequestRecording(mode);
        var child = builder(model, "child");
        parent.sessionHistoryConfigurer().accept(child);
        for (var builder : List.of(parent, child)) {
            try (var agent = builder.build()) {
                var context = RuntimeContext.builder().userId("u").sessionId("s").build();
                agent.call(List.of(new UserMessage("hello")), context)
                        .block(Duration.ofSeconds(10));
                var request =
                        agent.getDelegate().sessionLog(context).readAfter(0, 1000).stream()
                                .filter(e -> e.type().equals("request/prepared"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        mode == SessionRequestRecording.FULL,
                        request.data().containsKey("messages"));
                assertEquals(
                        mode == SessionRequestRecording.METADATA,
                        request.data().containsKey("messageCount"));
            }
        }
    }

    private HarnessAgent.Builder builder(Model model, String name) {
        return HarnessAgent.builder()
                .name(name)
                .agentId(name)
                .model(model)
                .workspace(workspace.resolve(name))
                .sessionLogStore(new InMemorySessionLogStore())
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents();
    }
}
