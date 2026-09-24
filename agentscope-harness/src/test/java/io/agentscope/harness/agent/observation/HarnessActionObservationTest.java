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
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.observation.ActionObserver;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessActionObservationTest {
    @TempDir Path workspace;
    @TempDir Path stateDirectory;

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
        var store = new JsonFileAgentStateStore(stateDirectory);
        var reader = new StateStoreActionObserver(store);
        try (var agent =
                HarnessAgent.builder()
                        .name("observed")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(store)
                        .toolkit(toolkit)
                        .build()) {
            var events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("go")
                                                    .build()),
                                    RuntimeContext.builder()
                                            .userId("user")
                                            .sessionId("session")
                                            .build())
                            .doOnNext(
                                    event -> {
                                        if (event instanceof CustomEvent custom
                                                && ActionObserver.EVENT_NAME.equals(
                                                        custom.getName())
                                                && "RETURNED"
                                                        .equals(custom.getValue().get("status"))) {
                                            String actionId =
                                                    (String) custom.getValue().get("action_id");
                                            var record =
                                                    reader.load("user", "session", actionId, false)
                                                            .orElseThrow();
                                            assertEquals(
                                                    ActionObservation.Status.RETURNED,
                                                    record.observation().status());
                                            assertEquals("call", record.result().getId());
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
