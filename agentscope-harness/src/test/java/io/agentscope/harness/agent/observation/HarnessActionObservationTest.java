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
package io.agentscope.harness.agent.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.observation.ActionObserver;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessActionObservationTest {
    @TempDir Path workspace;

    @Test
    void defaultHarnessPersistsBeforePublishingSettledEvent() {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        var use =
                ToolUseBlock.builder()
                        .id("call")
                        .name("probe")
                        .input(Map.of())
                        .content("{}")
                        .build();
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "first", List.of(use), null, Map.of(), "tool_use")))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "last",
                                        List.of(TextBlock.builder().text("done").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        var toolkit = new Toolkit();
        toolkit.registerTool(new Probe());
        var store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        try (var agent =
                HarnessAgent.builder()
                        .name("observed")
                        .model(model)
                        .workspace(workspace)
                        .sessionLogStore(store)
                        .toolkit(toolkit)
                        .build()) {
            var events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("go")
                                                    .build()),
                                    context)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof CustomEvent custom
                                                && ActionObserver.EVENT_NAME.equals(
                                                        custom.getName())
                                                && "RETURNED"
                                                        .equals(custom.getValue().get("status"))) {
                                            String actionId =
                                                    (String) custom.getValue().get("action_id");
                                            var log = agent.getDelegate().sessionLog(context);
                                            var record =
                                                    StreamSupport.stream(
                                                                    log.scan(0, log.head().seq())
                                                                            .spliterator(),
                                                                    false)
                                                            .filter(
                                                                    entry ->
                                                                            entry.type()
                                                                                    .equals(
                                                                                            "action/end"))
                                                            .map(entry -> entry.data())
                                                            .filter(
                                                                    data ->
                                                                            actionId.equals(
                                                                                    ((Map<?, ?>)
                                                                                                    data
                                                                                                            .get(
                                                                                                                    "observation"))
                                                                                            .get(
                                                                                                    "actionId")))
                                                            .findFirst()
                                                            .orElseThrow();
                                            assertEquals(
                                                    "RETURNED",
                                                    ((Map<?, ?>) record.get("observation"))
                                                            .get("status"));
                                            assertEquals(
                                                    "call",
                                                    ((Map<?, ?>) record.get("result")).get("id"));
                                        }
                                    })
                            .collectList()
                            .block(Duration.ofSeconds(20));
            assertTrue(
                    events.stream()
                            .filter(CustomEvent.class::isInstance)
                            .map(CustomEvent.class::cast)
                            .anyMatch(
                                    event ->
                                            ActionObserver.EVENT_NAME.equals(event.getName())
                                                    && "RETURNED"
                                                            .equals(
                                                                    event.getValue()
                                                                            .get("status"))));
        }
    }

    public static class Probe {
        @Tool(description = "Read a fixed probe value", readOnly = true)
        public String probe() {
            return "observed";
        }
    }
}
