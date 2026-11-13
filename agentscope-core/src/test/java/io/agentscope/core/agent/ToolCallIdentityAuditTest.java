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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ToolCallIdentityAuditTest {
    public static final class AuditTool {
        private final AtomicInteger runs = new AtomicInteger();

        @Tool(description = "Returns a local audit result")
        public String audit() {
            runs.incrementAndGet();
            return "tool ok";
        }
    }

    @ParameterizedTest(name = "provider ID present: {0}")
    @ValueSource(booleans = {true, false})
    void toolIdShouldMatchAcrossStreamingAndHistory(boolean providerId) {
        AtomicInteger requests = new AtomicInteger();
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "identity-audit";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
                        if (requests.incrementAndGet() > 1) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .id("answer")
                                            .content(
                                                    List.of(TextBlock.builder().text("ok").build()))
                                            .build());
                        }
                        ChatResponse first =
                                ChatResponse.builder()
                                        .id("tool-round")
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id(
                                                                        providerId
                                                                                ? "provider-42"
                                                                                : null)
                                                                .name("audit")
                                                                .content("{")
                                                                .build()))
                                        .build();
                        ChatResponse second =
                                ChatResponse.builder()
                                        .id("tool-round")
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .name("__fragment__")
                                                                .content("}")
                                                                .build()))
                                        .build();
                        return Flux.concat(
                                Mono.just(first),
                                Mono.delay(Duration.ofMillis(40)).thenReturn(second),
                                Mono.delay(Duration.ofMillis(40))
                                        .thenReturn(
                                                ChatResponse.builder()
                                                        .id("tool-round")
                                                        .content(List.of())
                                                        .build()));
                    }
                };
        AuditTool tool = new AuditTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        ReActAgent agent = ReActAgent.builder().name("audit").model(model).toolkit(toolkit).build();
        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("call the audit tool")))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events);
        List<String> starts =
                events.stream()
                        .filter(ToolCallStartEvent.class::isInstance)
                        .map(ToolCallStartEvent.class::cast)
                        .map(ToolCallStartEvent::getToolCallId)
                        .toList();
        List<String> deltas =
                events.stream()
                        .filter(ToolCallDeltaEvent.class::isInstance)
                        .map(ToolCallDeltaEvent.class::cast)
                        .map(ToolCallDeltaEvent::getToolCallId)
                        .toList();
        List<String> ends =
                events.stream()
                        .filter(ToolCallEndEvent.class::isInstance)
                        .map(ToolCallEndEvent.class::cast)
                        .map(ToolCallEndEvent::getToolCallId)
                        .toList();
        List<String> results =
                events.stream()
                        .filter(ToolResultStartEvent.class::isInstance)
                        .map(ToolResultStartEvent.class::cast)
                        .map(ToolResultStartEvent::getToolCallId)
                        .toList();
        List<Msg> history = agent.getAgentState().getContext();
        String historyCall =
                history.stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                        .findFirst()
                        .orElseThrow()
                        .getId();
        String historyResult =
                history.stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                        .findFirst()
                        .orElseThrow()
                        .getId();
        if (providerId) {
            assertEquals("provider-42", historyCall);
        }
        assertEquals(1, tool.runs.get());
        assertEquals(historyCall, historyResult);
        assertEquals(List.of(historyCall), results);
        assertEquals(List.of(historyCall), starts, "Tool call IDs must remain stable");
        assertEquals(List.of(historyCall, historyCall), deltas);
        assertEquals(List.of(historyCall), ends);
    }
}
