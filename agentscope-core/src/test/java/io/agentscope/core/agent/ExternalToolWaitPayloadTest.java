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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ExternalToolWaitPayloadTest {

    @Test
    void journalsExternalToolWaitWithLargeArguments() {
        String query = "a".repeat(1536 * 1024);
        String arguments = "{\"query\":\"" + query + "\"}";
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("external-1")
                        .name("external_api")
                        .content(arguments)
                        .build();
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "external-wait-payload";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(ChatResponse.builder().content(List.of(call)).build());
                    }
                };
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Executes externally")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string")),
                                        "required",
                                        List.of("query")))
                        .build());
        RuntimeContext context = RuntimeContext.builder().userId("u").sessionId("s").build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .sessionLogStore(new InMemorySessionLogStore())
                        .build()) {
            List<AgentEvent> events =
                    agent.streamEvents("run the external tool", context)
                            .collectList()
                            .block(Duration.ofSeconds(30));
            assertNotNull(events);
            RequireExternalExecutionEvent request =
                    events.stream()
                            .filter(RequireExternalExecutionEvent.class::isInstance)
                            .map(RequireExternalExecutionEvent.class::cast)
                            .findFirst()
                            .orElseThrow();
            ToolUseBlock requestedCall = request.getToolCalls().get(0);
            assertEquals(arguments.length(), requestedCall.getContent().length());
            assertEquals(query.length(), ((String) requestedCall.getInput().get("query")).length());
            Msg suspended =
                    events.stream()
                            .filter(AgentResultEvent.class::isInstance)
                            .map(AgentResultEvent.class::cast)
                            .findFirst()
                            .orElseThrow()
                            .getResult();
            assertEquals(GenerateReason.TOOL_SUSPENDED, suspended.getGenerateReason());
            assertEquals(
                    Set.of("external-1"),
                    SessionInteractions.pending(agent.sessionLog(context)).keySet());
        }
    }
}
